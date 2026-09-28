package api

import (
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
	return New(cfg, st, auth.NewManager("test-secret", time.Minute, time.Hour), hub.New(log), log)
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
