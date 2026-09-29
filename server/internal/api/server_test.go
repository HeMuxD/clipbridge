package api

import (
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/clipbridge/server/internal/auth"
	"github.com/clipbridge/server/internal/config"
	"github.com/clipbridge/server/internal/hub"
	"github.com/clipbridge/server/internal/model"
	"github.com/clipbridge/server/internal/store"
)

// newTestServer 建一个用临时目录做存储的 Server，避免落盘污染仓库
func newTestServer(t *testing.T) *Server {
	t.Helper()

	dir := t.TempDir()
	files := filepath.Join(dir, "files")

	st, err := store.Open(filepath.Join(dir, "test.db"), files, 7)
	if err != nil {
		t.Fatalf("打开 store 失败: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	cfg := config.Default()
	cfg.Server.PublicBaseURL = "https://clip.example.com/"
	cfg.Storage.FileDir = files

	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	return New(cfg, st,
		auth.NewManager("test-secret", auth.PairCodePolicy{TTL: time.Minute}, time.Hour),
		hub.New(log), log)
}

// TestStatusWriterKeepsHijacker 是 WebSocket 能否工作的前提。
// websocket.Upgrader 会断言 ResponseWriter 实现了 http.Hijacker，
// 而中间件的包装结构体并不会提升这个可选接口 ——
// 一旦丢失，所有 /ws/v1 升级都会以 500 失败。
func TestStatusWriterKeepsHijacker(t *testing.T) {
	var w http.ResponseWriter = &statusWriter{ResponseWriter: httptest.NewRecorder(), status: 200}

	if _, ok := w.(http.Hijacker); !ok {
		t.Fatal("statusWriter 未实现 http.Hijacker，WebSocket 升级会返回 500")
	}
	if _, ok := w.(http.Flusher); !ok {
		t.Fatal("statusWriter 未实现 http.Flusher")
	}
}

// TestStatusWriterRecordsStatus 确保包装后仍能记录状态码
func TestStatusWriterRecordsStatus(t *testing.T) {
	rec := httptest.NewRecorder()
	sw := &statusWriter{ResponseWriter: rec, status: 200}

	sw.WriteHeader(http.StatusTeapot)

	if sw.status != http.StatusTeapot {
		t.Fatalf("记录的状态码为 %d，期望 %d", sw.status, http.StatusTeapot)
	}
	if rec.Code != http.StatusTeapot {
		t.Fatalf("透传的状态码为 %d，期望 %d", rec.Code, http.StatusTeapot)
	}
}

// TestDownloadURLKeepsExtension 覆盖历史记录与离线补推曾经的 404 bug：
// 磁盘上的文件名是 fileId + 扩展名，URL 少了扩展名就取不到图。
func TestDownloadURLKeepsExtension(t *testing.T) {
	s := newTestServer(t)

	cases := []struct {
		mime string
		want string
	}{
		{"image/png", "https://clip.example.com/f/aabb.png"},
		{"image/jpeg", "https://clip.example.com/f/aabb.jpg"},
		{"image/webp", "https://clip.example.com/f/aabb.webp"},
		{"image/gif", "https://clip.example.com/f/aabb.gif"},
	}
	for _, c := range cases {
		if got := s.downloadURL("aabb", c.mime); got != c.want {
			t.Errorf("downloadURL(aabb, %q) = %q，期望 %q", c.mime, got, c.want)
		}
	}

	// mime 缺失时（历史数据）应回退到磁盘上的真实文件名
	if err := os.WriteFile(filepath.Join(s.cfg.Storage.FileDir, "ccdd.webp"), []byte("x"), 0o644); err != nil {
		t.Fatalf("写入测试文件失败: %v", err)
	}
	if got, want := s.downloadURL("ccdd", ""), "https://clip.example.com/f/ccdd.webp"; got != want {
		t.Errorf("mime 缺失时的 downloadURL = %q，期望 %q", got, want)
	}
}

// TestExtFromMimeMirrorsSniffImage 保证 MIME → 扩展名的映射与上传时的魔数识别一致
func TestExtFromMimeMirrorsSniffImage(t *testing.T) {
	pairs := map[string]string{
		"image/png":  ".png",
		"image/jpeg": ".jpg",
		"image/webp": ".webp",
		"image/gif":  ".gif",
		"":           "",
		"text/plain": "",
	}
	for mime, want := range pairs {
		if got := extFromMime(mime); got != want {
			t.Errorf("extFromMime(%q) = %q，期望 %q", mime, got, want)
		}
	}
}

// TestHandleFileRejectsIllegalNames 校验内置静态服务不会越权读文件
func TestHandleFileRejectsIllegalNames(t *testing.T) {
	s := newTestServer(t)

	illegal := []string{
		"/f/",
		"/f/../config.yaml",
		"/f/../../etc/passwd",
		"/f/abc.png",                                  // fileId 长度不对
		"/f/" + strings.Repeat("a", 32) + ".txt",      // 扩展名不在白名单
		"/f/" + strings.Repeat("A", 32) + ".png",      // 必须是小写十六进制
		"/f/" + strings.Repeat("a", 32) + ".png/../x", // 带路径分隔
	}
	for _, name := range illegal {
		req := httptest.NewRequest(http.MethodGet, name, nil)
		rec := httptest.NewRecorder()
		s.handleFile(rec, req)

		if rec.Code != http.StatusNotFound {
			t.Errorf("请求 %q 得到 %d，期望 404", name, rec.Code)
		}
	}
}

// TestHandleFileServesExistingImage 确认合法名字能真正取到文件
func TestHandleFileServesExistingImage(t *testing.T) {
	s := newTestServer(t)

	fileID := strings.Repeat("ab", 16)
	content := []byte("fake-png-bytes")
	if err := os.WriteFile(filepath.Join(s.cfg.Storage.FileDir, fileID+".png"), content, 0o644); err != nil {
		t.Fatalf("写入测试文件失败: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/f/"+fileID+".png", nil)
	rec := httptest.NewRecorder()
	s.handleFile(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("取图得到 %d，期望 200", rec.Code)
	}
	if got := rec.Body.Bytes(); string(got) != string(content) {
		t.Fatalf("内容不一致：%q", got)
	}
}

// ---------- 「只同步当前这一次操作」 ----------

// 造一个已配对但当前离线的设备
func addOfflineDevice(t *testing.T, s *Server, id string) {
	t.Helper()
	err := s.store.UpsertDevice(&model.Device{
		DeviceID:   id,
		DeviceName: id,
		Platform:   "android",
		TokenHash:  "deadbeef",
		PairedAt:   time.Now().UnixMilli(),
	})
	if err != nil {
		t.Fatalf("写入设备失败: %v", err)
	}
}

// 存一条内容，返回自增 ID
func saveTestClip(t *testing.T, s *Server) int64 {
	t.Helper()
	id, err := s.store.SaveClip(&model.Clip{
		MsgID:     "msg-1",
		Hash:      "hash-1",
		Kind:      model.KindText,
		Origin:    model.OriginClipboard,
		SrcDevice: "sender",
		SrcName:   "发送端",
		Text:      "hello",
		CreatedAt: time.Now().UnixMilli(),
	})
	if err != nil {
		t.Fatalf("保存内容失败: %v", err)
	}
	return id
}

// TestOfflineReplayDisabledByDefault 是「以往的复制或截图不管」的回归测试：
// 默认补推窗口为 0，离线设备不该被入队，上线时也拿不到旧内容。
func TestOfflineReplayDisabledByDefault(t *testing.T) {
	s := newTestServer(t)

	if s.cfg.Limits.OfflineReplaySeconds != 0 {
		t.Fatalf("默认补推窗口应为 0，实际为 %d", s.cfg.Limits.OfflineReplaySeconds)
	}

	addOfflineDevice(t, s, "offline-dev")
	clipID := saveTestClip(t, s)

	s.enqueueForOffline(clipID, "sender")

	pending, err := s.store.DrainPending("offline-dev")
	if err != nil {
		t.Fatalf("读取离线队列失败: %v", err)
	}
	if len(pending) != 0 {
		t.Fatalf("补推关闭时不应入队，实际有 %d 条", len(pending))
	}
}

// TestOfflineReplayWindowEnqueues 打开补推窗口后，离线设备应能拿到内容
func TestOfflineReplayWindowEnqueues(t *testing.T) {
	s := newTestServer(t)
	s.cfg.Limits.OfflineReplaySeconds = 60

	addOfflineDevice(t, s, "offline-dev")
	clipID := saveTestClip(t, s)

	s.enqueueForOffline(clipID, "sender")

	pending, err := s.store.DrainPending("offline-dev")
	if err != nil {
		t.Fatalf("读取离线队列失败: %v", err)
	}
	if len(pending) != 1 {
		t.Fatalf("补推开启时应入队 1 条，实际 %d 条", len(pending))
	}
}

// TestOfflineReplayExpiredEntriesAreDropped 验证"补推窗口之外的内容会被自然丢弃"：
// 入队 TTL 取的就是补推窗口，过期的记录在 DrainPending 时被过滤掉。
func TestOfflineReplayExpiredEntriesAreDropped(t *testing.T) {
	s := newTestServer(t)
	// 窗口取负数，等价于"入队即过期"
	s.cfg.Limits.OfflineReplaySeconds = -1

	addOfflineDevice(t, s, "offline-dev")
	clipID := saveTestClip(t, s)

	s.enqueueForOffline(clipID, "sender")

	pending, err := s.store.DrainPending("offline-dev")
	if err != nil {
		t.Fatalf("读取离线队列失败: %v", err)
	}
	if len(pending) != 0 {
		t.Fatalf("已过期的队列项不应被投递，实际 %d 条", len(pending))
	}
}

// ---------- 配对码：多设备复用 + 按 IP 防爆破 ----------

// pairOnce 发一次配对请求。ip 非空时以 X-Real-IP 注入，模拟 Nginx 前置的真实场景。
func pairOnce(t *testing.T, s *Server, code, deviceID, ip string) *httptest.ResponseRecorder {
	t.Helper()
	body := fmt.Sprintf(
		`{"pairCode":%q,"deviceId":%q,"deviceName":%q,"platform":"android"}`,
		code, deviceID, deviceID)
	req := httptest.NewRequest(http.MethodPost, "/api/pair", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if ip != "" {
		req.Header.Set("X-Real-IP", ip)
	}
	rec := httptest.NewRecorder()
	s.handlePair(rec, req)
	return rec
}

// TestPairAllowsMultipleDevices 是本次修复的核心回归测试。
//
// 曾经的语义是"配对成功即作废"，导致第二台设备必然拿到
// "配对码无效或已过期"，用户只能重启服务端换码 —— 多设备用户每次都要踩一次。
func TestPairAllowsMultipleDevices(t *testing.T) {
	s := newTestServer(t)
	code, _ := s.auth.CurrentPairCode()

	for _, dev := range []string{"phone-a", "phone-b", "windows-pc"} {
		rec := pairOnce(t, s, code, dev, "203.0.113.7")
		if rec.Code != http.StatusOK {
			t.Fatalf("设备 %s 配对失败：HTTP %d，响应=%s", dev, rec.Code, rec.Body.String())
		}
		if !strings.Contains(rec.Body.String(), `"token"`) {
			t.Fatalf("设备 %s 的响应里没有 token：%s", dev, rec.Body.String())
		}
	}

	devices, err := s.store.ListDevices()
	if err != nil {
		t.Fatalf("查询设备失败: %v", err)
	}
	if len(devices) != 3 {
		t.Fatalf("三台设备都应落库，实际 %d 台", len(devices))
	}
}

// TestPairRateLimitedPerIP 确认按来源 IP 的限流生效。
//
// 这是"配对码可以重复使用"的安全前提：配对码只有几位数字，
// 没有限流的话公网上的暴力枚举在几分钟内就能撞开。
func TestPairRateLimitedPerIP(t *testing.T) {
	s := newTestServer(t)
	code, _ := s.auth.CurrentPairCode()

	const ip = "198.51.100.9"
	limited := false
	for i := 0; i < pairRateLimitPerMinute+3; i++ {
		rec := pairOnce(t, s, code, fmt.Sprintf("dev-%d", i), ip)
		if rec.Code == http.StatusTooManyRequests {
			limited = true
			break
		}
	}
	if !limited {
		t.Fatalf("同一 IP 连续尝试超过 %d 次后应被限流", pairRateLimitPerMinute)
	}

	// 限流是按 IP 的，换个来源不应被同一把锁拦住
	if rec := pairOnce(t, s, code, "other-device", "198.51.100.10"); rec.Code != http.StatusOK {
		t.Fatalf("不同来源 IP 不应受影响，实际 HTTP %d", rec.Code)
	}
}

// TestPairRejectsWrongCode 确认错误/缺失的配对码仍然被拒
func TestPairRejectsWrongCode(t *testing.T) {
	s := newTestServer(t)
	code, _ := s.auth.CurrentPairCode()

	wrong := "00000000"
	if code == wrong {
		wrong = "11111111"
	}
	if rec := pairOnce(t, s, wrong, "dev-x", "203.0.113.20"); rec.Code != http.StatusUnauthorized {
		t.Fatalf("错误配对码应返回 401，实际 %d", rec.Code)
	}
	if rec := pairOnce(t, s, code, "", "203.0.113.20"); rec.Code != http.StatusBadRequest {
		t.Fatalf("deviceId 为空应返回 400，实际 %d", rec.Code)
	}
}

// TestClientIP 覆盖限流的取 IP 逻辑。
// 生产环境 Go 进程前面有 Nginx，r.RemoteAddr 恒为回环地址 ——
// 若只看它，所有客户端会被合并成同一个限流桶。
func TestClientIP(t *testing.T) {
	cases := []struct {
		name, realIP, xff, remoteAddr, want string
	}{
		{"X-Real-IP 优先", "203.0.113.1", "198.51.100.2, 10.0.0.1", "127.0.0.1:1234", "203.0.113.1"},
		{"回退到 X-Forwarded-For 最左值", "", "198.51.100.2, 10.0.0.1", "127.0.0.1:1234", "198.51.100.2"},
		{"再回退到 RemoteAddr", "", "", "203.0.113.9:5555", "203.0.113.9"},
	}
	for _, c := range cases {
		req := httptest.NewRequest(http.MethodPost, "/api/pair", nil)
		req.RemoteAddr = c.remoteAddr
		if c.realIP != "" {
			req.Header.Set("X-Real-IP", c.realIP)
		}
		if c.xff != "" {
			req.Header.Set("X-Forwarded-For", c.xff)
		}
		if got := clientIP(req); got != c.want {
			t.Errorf("%s：clientIP = %q，期望 %q", c.name, got, c.want)
		}
	}
}
