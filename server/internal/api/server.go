package api

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/clipbridge/server/internal/auth"
	"github.com/clipbridge/server/internal/config"
	"github.com/clipbridge/server/internal/hub"
	"github.com/clipbridge/server/internal/model"
	"github.com/clipbridge/server/internal/store"

	"github.com/gorilla/websocket"
)

// Server 聚合 HTTP 路由所需的全部依赖
type Server struct {
	cfg   *config.Config
	store *store.Store
	auth  *auth.Manager
	hub   *hub.Hub
	log   *slog.Logger
	rl    *rateLimiter
	// pairRL 按来源 IP 限制 /api/pair 的尝试次数。
	// 与 rl 分开是必须的：rl 按 deviceId 计数，而配对恰恰发生在拿到 deviceId 之前。
	pairRL *rateLimiter

	upgrader websocket.Upgrader
	// 服务端侧敏感内容过滤规则
	filters []*regexp.Regexp
}

// New 构造 Server
func New(cfg *config.Config, st *store.Store, am *auth.Manager, h *hub.Hub, log *slog.Logger) *Server {
	s := &Server{
		cfg:    cfg,
		store:  st,
		auth:   am,
		hub:    h,
		log:    log,
		rl:     newRateLimiter(cfg.Storage.RateLimitPerMinute),
		pairRL: newRateLimiter(pairRateLimitPerMinute),
		upgrader: websocket.Upgrader{
			ReadBufferSize:  4096,
			WriteBufferSize: 4096,
			// 注意：这里放行所有来源，仅适用于自建自用场景。
			// 若对外提供服务，必须改为白名单校验，否则存在跨站 WebSocket 劫持风险。
			CheckOrigin: func(r *http.Request) bool { return true },
		},
	}
	if cfg.Filter.Enabled {
		for _, p := range cfg.Filter.Patterns {
			re, err := regexp.Compile(p)
			if err != nil {
				log.Warn("过滤规则编译失败，已跳过", "pattern", p, "err", err)
				continue
			}
			s.filters = append(s.filters, re)
		}
		log.Info("敏感内容过滤已启用", "rules", len(s.filters))
	}
	return s
}

// Routes 注册全部 HTTP 路由
func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("/healthz", s.handleHealth)
	mux.HandleFunc("/api/pair", s.handlePair)
	mux.HandleFunc("/api/upload", s.handleUpload)
	mux.HandleFunc("/api/history", s.handleHistory)
	mux.HandleFunc("/api/devices", s.handleDevices)
	mux.HandleFunc("/ws/v1", s.handleWS)

	// 图片下载。生产环境通常会由 Nginx 在 /f/ 处直接命中静态文件，
	// 请求不会走到这里；容器化 / 单进程部署时则由本进程直接提供，
	// 免去"Go 写文件、Nginx 读文件"必须共享同一个卷的麻烦。
	mux.HandleFunc("/f/", s.handleFile)

	return s.withLogging(mux)
}

// ---------- 中间件 ----------

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	w.status = code
	w.ResponseWriter.WriteHeader(code)
}

// Hijack 必须显式转发。
// websocket.Upgrader 会断言 ResponseWriter 是否实现 http.Hijacker 来接管连接，
// 而结构体包装 http.ResponseWriter 接口只会提升接口自身的三个方法，
// Hijack 不在其中 —— 少了这个方法，所有 WebSocket 升级都会以
// 500 "response does not implement http.Hijacker" 失败。
func (w *statusWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	h, ok := w.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, errors.New("底层 ResponseWriter 不支持 Hijack")
	}
	return h.Hijack()
}

// Flush 同理：长连接与流式响应依赖它
func (w *statusWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func (s *Server) withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: 200}
		next.ServeHTTP(sw, r)
		s.log.Debug("请求完成",
			"method", r.Method, "path", r.URL.Path,
			"status", sw.status, "cost", time.Since(start).String())
	})
}

// ---------- 通用响应 ----------

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, errCode int, msg string) {
	writeJSON(w, code, map[string]any{"code": errCode, "message": msg})
}

// bearerToken 从 Authorization 头解析 Bearer Token
func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if len(h) > len(prefix) && strings.EqualFold(h[:len(prefix)], prefix) {
		return h[len(prefix):]
	}
	return ""
}

// authenticate 校验 Token 并确认设备已配对且未吊销
func (s *Server) authenticate(r *http.Request) (*model.Device, error) {
	token := bearerToken(r)
	if token == "" {
		return nil, errors.New("缺少 Token")
	}
	deviceID, err := s.auth.VerifyToken(token)
	if err != nil {
		return nil, err
	}
	dev, err := s.store.GetDevice(deviceID, auth.HashToken(token))
	if err != nil {
		return nil, fmt.Errorf("设备校验失败: %w", err)
	}
	return dev, nil
}

// ---------- 健康检查 ----------

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status":  "ok",
		"online":  s.hub.Count(),
		"version": "1.0.0",
		"time":    time.Now().UnixMilli(),
	})
}

// ---------- 配对 ----------

type pairRequest struct {
	PairCode   string `json:"pairCode"`
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	Platform   string `json:"platform"`
}

// pairRateLimitPerMinute 是 /api/pair 每个来源 IP 每分钟允许的尝试次数。
//
// 必须限流的原因：配对码只有 6 位数字，而且在有效期内【可以重复使用】（见 handlePair 说明）。
// 10 次/分钟意味着穷举 100 万种组合约需 69 天，远长于配对码本身的有效期，
// 所以"可重复使用"带来的额外风险被这个上限压住了。
const pairRateLimitPerMinute = 10

// clientIP 取出请求的真实来源 IP，用于按 IP 限流。
//
// 生产部署里 Go 进程前面有一层 Nginx，此时 r.RemoteAddr 恒为 Nginx 的回环地址，
// 直接按它限流等于把所有客户端合并成同一个桶。所以优先读 Nginx 注入的头
// （见 nginx.http.conf.template / nginx.https.conf.template 里的 X-Real-IP 与 X-Forwarded-For）。
func clientIP(r *http.Request) string {
	if v := strings.TrimSpace(r.Header.Get("X-Real-IP")); v != "" {
		return v
	}
	if v := r.Header.Get("X-Forwarded-For"); v != "" {
		// X-Forwarded-For 形如 "client, proxy1, proxy2"，最左边那个才是真实客户端
		if i := strings.IndexByte(v, ','); i >= 0 {
			v = v[:i]
		}
		if v = strings.TrimSpace(v); v != "" {
			return v
		}
	}
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return host
	}
	return r.RemoteAddr
}

// handlePair 用配对码换取长期 Token。
//
// 配对码在有效期内【可以重复使用】，不会一配对成功就作废。
// 理由：一个家庭通常有多台设备（笔记本 + 手机 + 平板），一次性配对码会逼着用户
// 每加一台设备就重启一次服务端 —— 而配对码存在进程内存里，没有别的换码途径。
// 代价是有效期内的暴力枚举窗口，所以这里配合了按来源 IP 的限流（pairRateLimitPerMinute）。
//
// 想立刻换码：重启进程即可（配对码是进程内存态，会随启动重新生成）。
func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, model.ErrInternal, "仅支持 POST")
		return
	}

	ip := clientIP(r)
	if !s.pairRL.Allow(ip) {
		s.log.Warn("配对请求被限流", "ip", ip)
		writeErr(w, http.StatusTooManyRequests, model.ErrRateLimited, "尝试过于频繁，请稍后再试")
		return
	}

	var req pairRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, model.ErrInternal, "请求体解析失败")
		return
	}

	if req.DeviceID == "" || req.DeviceName == "" {
		writeErr(w, http.StatusBadRequest, model.ErrInternal, "deviceId 与 deviceName 不能为空")
		return
	}

	// consume=false：有效期内可重复使用，多台设备用同一个码配对。
	// 防爆破由上面的 pairRL 负责，不要改回 true —— 那会让第二台设备必然配不上。
	if err := s.auth.VerifyPairCode(req.PairCode, false); err != nil {
		s.log.Warn("配对失败", "deviceId", req.DeviceID, "ip", ip, "reason", err)
		writeErr(w, http.StatusUnauthorized, model.ErrDeviceNotPaired, "配对码无效或已过期")
		return
	}

	token, expiresAt := s.auth.IssueToken(req.DeviceID)

	dev := &model.Device{
		DeviceID:   req.DeviceID,
		DeviceName: req.DeviceName,
		Platform:   req.Platform,
		TokenHash:  auth.HashToken(token),
		PairedAt:   time.Now().UnixMilli(),
	}
	if err := s.store.UpsertDevice(dev); err != nil {
		s.log.Error("写入设备失败", "err", err)
		writeErr(w, http.StatusInternalServerError, model.ErrInternal, "服务端内部错误")
		return
	}

	s.log.Info("新设备配对成功",
		"deviceId", req.DeviceID, "name", req.DeviceName, "platform", req.Platform)

	writeJSON(w, http.StatusOK, map[string]any{
		"token":     token,
		"deviceId":  req.DeviceID,
		"expiresAt": expiresAt.UnixMilli(),
	})
}

// ---------- 上传 ----------

// handleUpload 接收图片二进制。
// 图片走 HTTP 而非 WebSocket，避免大 payload 阻塞长连接。
func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, model.ErrInternal, "仅支持 POST")
		return
	}

	dev, err := s.authenticate(r)
	if err != nil {
		writeErr(w, http.StatusUnauthorized, model.ErrInvalidToken, "Token 无效或已过期")
		return
	}
	if !s.rl.Allow(dev.DeviceID) {
		writeErr(w, http.StatusTooManyRequests, model.ErrRateLimited, "请求过于频繁")
		return
	}

	// MaxBytesReader 在超出限制时直接中断读取，避免内存被打爆
	r.Body = http.MaxBytesReader(w, r.Body, s.cfg.Storage.MaxFileSize)
	if err := r.ParseMultipartForm(8 << 20); err != nil {
		writeErr(w, http.StatusRequestEntityTooLarge, model.ErrFileTooLarge,
			fmt.Sprintf("文件过大或表单解析失败（上限 %d MB）", s.cfg.Storage.MaxFileSize>>20))
		return
	}

	file, header, err := r.FormFile("file")
	if err != nil {
		writeErr(w, http.StatusBadRequest, model.ErrInternal, "缺少 file 字段")
		return
	}
	defer file.Close()

	data, err := io.ReadAll(file)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, model.ErrInternal, "读取文件失败")
		return
	}

	// 依据魔数判断真实类型，不信任客户端声称的 Content-Type
	ext, mime := sniffImage(data)
	if ext == "" {
		writeErr(w, http.StatusBadRequest, model.ErrInternal, "仅支持 PNG / JPEG / WebP / GIF 图片")
		return
	}

	// 文件名用内容哈希，天然实现相同图片只存一份
	sum := sha256.Sum256(data)
	fileID := hex.EncodeToString(sum[:16])

	if err := s.store.SaveFile(fileID, ext, data); err != nil {
		s.log.Error("保存文件失败", "err", err)
		writeErr(w, http.StatusInternalServerError, model.ErrInternal, "保存文件失败")
		return
	}

	// 客户端可能已带 hash，此时以服务端计算值为准
	clientHash := r.FormValue("hash")
	if clientHash == "" {
		clientHash = hex.EncodeToString(sum[:])
	}

	s.log.Info("文件上传完成",
		"deviceId", dev.DeviceID, "fileId", fileID, "size", len(data),
		"filename", filepath.Base(header.Filename))

	writeJSON(w, http.StatusOK, map[string]any{
		"fileId": fileID,
		"url":    s.fileURL(fileID, ext),
		"mime":   mime,
		"size":   len(data),
		"hash":   clientHash,
	})
}

// fileURL 拼接对外可访问的下载地址
func (s *Server) fileURL(fileID, ext string) string {
	return strings.TrimRight(s.cfg.Server.PublicBaseURL, "/") + "/f/" + fileID + ext
}

// downloadURL 按 MIME 拼出下载地址。
// 磁盘上的文件名是 fileId + 扩展名，所以 URL 必须带上扩展名，
// 否则无论是 Nginx 的 /f/ 静态映射还是本进程的静态服务都找不到文件。
func (s *Server) downloadURL(fileID, mime string) string {
	ext := extFromMime(mime)
	if ext == "" {
		// 历史数据里 mime 可能为空，退化为按磁盘上的实际文件名推断
		ext = s.store.FindFileExt(fileID)
	}
	return s.fileURL(fileID, ext)
}

// extFromMime 把 MIME 映射回磁盘扩展名
func extFromMime(mime string) string {
	switch mime {
	case "image/png":
		return ".png"
	case "image/jpeg":
		return ".jpg"
	case "image/webp":
		return ".webp"
	case "image/gif":
		return ".gif"
	}
	return ""
}

// fileIDPattern 限定 /f/ 下允许访问的文件名：
// 32 位小写十六进制（fileID）+ 白名单扩展名。
// 用白名单正则而不是直接拼接路径，可以从根本上杜绝目录穿越与目录列举。
var fileIDPattern = regexp.MustCompile(`^[0-9a-f]{32}\.(png|jpg|jpeg|webp|gif)$`)

// handleFile 由服务进程直接提供图片下载。
// 若前面部署了 Nginx，/f/ 会先被 Nginx 命中，这里的实现不会被使用。
func (s *Server) handleFile(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/f/")
	if !fileIDPattern.MatchString(name) {
		http.NotFound(w, r)
		return
	}

	w.Header().Set("Cache-Control", "private, max-age=604800")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeFile(w, r, filepath.Join(s.cfg.Storage.FileDir, name))
}

// sniffImage 通过文件头魔数识别图片格式
func sniffImage(data []byte) (ext, mime string) {
	if len(data) < 12 {
		return "", ""
	}
	switch {
	case data[0] == 0x89 && string(data[1:4]) == "PNG":
		return ".png", "image/png"
	case data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF:
		return ".jpg", "image/jpeg"
	case string(data[0:4]) == "RIFF" && string(data[8:12]) == "WEBP":
		return ".webp", "image/webp"
	case string(data[0:3]) == "GIF":
		return ".gif", "image/gif"
	}
	return "", ""
}

// ---------- 历史记录 ----------

func (s *Server) handleHistory(w http.ResponseWriter, r *http.Request) {
	if _, err := s.authenticate(r); err != nil {
		writeErr(w, http.StatusUnauthorized, model.ErrInvalidToken, "Token 无效或已过期")
		return
	}

	clips, err := s.store.ListClips(100)
	if err != nil {
		s.log.Error("查询历史失败", "err", err)
		writeErr(w, http.StatusInternalServerError, model.ErrInternal, "查询失败")
		return
	}

	items := make([]map[string]any, 0, len(clips))
	for _, c := range clips {
		item := map[string]any{
			"msgId":     c.MsgID,
			"kind":      c.Kind,
			"origin":    c.Origin,
			"hash":      c.Hash,
			"srcDevice": c.SrcDevice,
			"srcName":   c.SrcName,
			"createdAt": c.CreatedAt,
		}
		if c.Kind == model.KindText {
			item["text"] = c.Text
		} else {
			item["fileId"] = c.FileID
			item["url"] = s.downloadURL(c.FileID, c.Mime)
			item["mime"] = c.Mime
			item["size"] = c.Size
		}
		items = append(items, item)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

// ---------- 设备列表 ----------

func (s *Server) handleDevices(w http.ResponseWriter, r *http.Request) {
	if _, err := s.authenticate(r); err != nil {
		writeErr(w, http.StatusUnauthorized, model.ErrInvalidToken, "Token 无效或已过期")
		return
	}
	devices, err := s.store.ListDevices()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, model.ErrInternal, "查询失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"devices": devices,
		"online":  s.hub.OnlineDevices(),
	})
}

// ---------- WebSocket ----------

// handleWS 建立长连接并处理该连接上的全部消息
func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	dev, err := s.authenticate(r)
	if err != nil {
		// 握手阶段失败只能返回 HTTP 错误，无法用 WebSocket 关闭码
		writeErr(w, http.StatusUnauthorized, model.ErrInvalidToken, "Token 无效或已过期")
		return
	}

	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		s.log.Warn("WebSocket 升级失败", "err", err)
		return
	}

	client := s.hub.NewClient(conn, dev.DeviceID, dev.DeviceName, dev.Platform)

	s.hub.Serve(client, s.onWSSMessage, func(c *hub.Client) {
		s.store.TouchDevice(c.DeviceID)
		// 上线后先把离线期间积压的内容补给它
		s.flushPending(c)
		// 通知其他设备"我上线了"
		s.broadcastDeviceList(c.DeviceID)
	})
}

// onWSSMessage 处理单条消息，返回需要回复给该客户端的内容
func (s *Server) onWSSMessage(client *hub.Client, raw []byte) any {
	var env model.Envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		s.log.Warn("消息解析失败", "deviceId", client.DeviceID, "err", err)
		return errorEnvelope(model.ErrInternal, "消息格式错误")
	}
	if env.MsgID == "" {
		env.MsgID = newID()
	}

	switch env.Type {
	case model.TypePing:
		return model.Envelope{Type: model.TypePong, MsgID: env.MsgID, TS: time.Now().UnixMilli()}

	case model.TypeClip:
		return s.handleClipMessage(client, env, raw)

	case model.TypeAck:
		// 客户端确认已收到，服务端无需额外处理
		return nil

	default:
		s.log.Debug("忽略未知消息类型", "type", env.Type, "deviceId", client.DeviceID)
		return nil
	}
}

// handleClipMessage 是同步的核心路径：
// 回填来源 → 限流 → 过滤 → 去重 → 落库 → 广播 → 离线入队
func (s *Server) handleClipMessage(client *hub.Client, env model.Envelope, raw []byte) any {
	var p model.ClipPayload
	if err := remarshal(env.Payload, &p); err != nil {
		return errorEnvelope(model.ErrInternal, "clip payload 解析失败")
	}

	if p.Hash == "" {
		return errorEnvelope(model.ErrInternal, "缺少 hash 字段")
	}
	if p.Kind != model.KindText && p.Kind != model.KindImage {
		return errorEnvelope(model.ErrInternal, "kind 必须是 text 或 image")
	}
	if p.Kind == model.KindText && p.Text == "" {
		return errorEnvelope(model.ErrInternal, "文本内容为空")
	}
	if p.Kind == model.KindImage && p.FileID == "" {
		return errorEnvelope(model.ErrInternal, "图片缺少 fileId，请先调用 /api/upload")
	}

	if !s.rl.Allow(client.DeviceID) {
		return errorEnvelope(model.ErrRateLimited, "请求过于频繁，请稍后重试")
	}

	// 敏感内容二次校验：即使客户端漏判，服务端也要拦一道
	if p.Kind == model.KindText && s.matchesFilter(p.Text) {
		s.log.Warn("内容命中敏感词规则，已拦截", "deviceId", client.DeviceID)
		return ackEnvelope(env.MsgID, "error", model.ErrRateLimited, "内容命中敏感规则，已阻止同步")
	}

	// 防回环第一道防线：同一 hash 在保留窗口内已同步过，直接忽略。
	// 场景：设备 A 收到 B 的内容后写入本地剪贴板，触发本地上报。
	dup, err := s.store.IsDuplicate(p.Hash)
	if err != nil {
		s.log.Error("去重查询失败", "err", err)
	}
	if dup {
		s.log.Debug("内容重复，已忽略", "hash", truncate(p.Hash, 12), "deviceId", client.DeviceID)
		return ackEnvelope(env.MsgID, "ok", model.ErrDuplicate, "内容重复，已忽略")
	}

	// 回填来源设备信息，客户端无需自己填
	p.SrcDevice = client.DeviceID
	p.SrcName = client.DeviceName

	clip := &model.Clip{
		MsgID:     env.MsgID,
		Hash:      p.Hash,
		Kind:      p.Kind,
		Origin:    p.Origin,
		SrcDevice: client.DeviceID,
		SrcName:   client.DeviceName,
		Text:      p.Text,
		FileID:    p.FileID,
		Mime:      p.Mime,
		Size:      p.Size,
		Width:     p.Width,
		Height:    p.Height,
		CreatedAt: time.Now().UnixMilli(),
	}

	clipID, err := s.store.SaveClip(clip)
	if err != nil {
		if errors.Is(err, store.ErrDuplicate) {
			return ackEnvelope(env.MsgID, "ok", model.ErrDuplicate, "消息重复，已忽略")
		}
		s.log.Error("保存内容失败", "err", err)
		return errorEnvelope(model.ErrInternal, "服务端保存失败")
	}

	out := model.Envelope{
		Type:    model.TypeClip,
		MsgID:   env.MsgID,
		TS:      time.Now().UnixMilli(),
		Payload: p,
	}

	delivered := s.hub.Broadcast(out, client.DeviceID)

	// 只服务"当前这一次"操作：没打开补推窗口时，离线设备直接错过这条内容，
	// 等它上线也不会再收到 —— 用户并不关心自己几分钟前复制过什么。
	if s.cfg.Limits.OfflineReplaySeconds > 0 {
		s.enqueueForOffline(clipID, client.DeviceID)
	}

	s.log.Info("内容已同步",
		"from", client.DeviceID, "kind", p.Kind, "origin", p.Origin,
		"bytes", len(raw), "delivered", delivered)

	return ackEnvelope(env.MsgID, "ok", 0, "")
}

// enqueueForOffline 为所有已配对但当前离线的设备入队。
// 入队 TTL 直接取"补推窗口"：比窗口更早的内容，即便留在这里，
// 也会在 DrainPending 时因 expires_at 已过而被过滤掉 —— 也就是"以往的不管"。
func (s *Server) enqueueForOffline(clipID int64, excludeDevice string) {
	devices, err := s.store.ListDevices()
	if err != nil {
		s.log.Error("查询设备列表失败", "err", err)
		return
	}

	ttl := time.Duration(s.cfg.Limits.OfflineReplaySeconds) * time.Second
	if h := time.Duration(s.cfg.Limits.OfflineQueueTTLHours) * time.Hour; h > 0 && h < ttl {
		ttl = h
	}

	for _, d := range devices {
		if d.DeviceID == excludeDevice || s.hub.IsOnline(d.DeviceID) {
			continue
		}
		if err := s.store.Enqueue(d.DeviceID, clipID, ttl); err != nil {
			s.log.Error("入队失败", "deviceId", d.DeviceID, "err", err)
		}
	}
}

// flushPending 设备上线时补推离线期间积压的内容。
// 补推窗口为 0（默认）时，这里只负责把残留的积压丢掉，不下发任何东西。
func (s *Server) flushPending(client *hub.Client) {
	clips, err := s.store.DrainPending(client.DeviceID)
	if err != nil {
		s.log.Error("读取离线队列失败", "deviceId", client.DeviceID, "err", err)
		return
	}
	if len(clips) == 0 {
		return
	}

	if s.cfg.Limits.OfflineReplaySeconds <= 0 {
		// DrainPending 已经把这批记录取走了，这里直接丢弃即可
		s.log.Info("已丢弃离线积压（未开启补推）",
			"deviceId", client.DeviceID, "count", len(clips))
		return
	}

	s.log.Info("补推离线内容", "deviceId", client.DeviceID, "count", len(clips))

	for _, c := range clips {
		p := model.ClipPayload{
			Kind:      c.Kind,
			Hash:      c.Hash,
			Origin:    c.Origin,
			Text:      c.Text,
			FileID:    c.FileID,
			Mime:      c.Mime,
			Size:      c.Size,
			Width:     c.Width,
			Height:    c.Height,
			SrcDevice: c.SrcDevice,
			SrcName:   c.SrcName,
		}
		if c.Kind == model.KindImage && c.FileID != "" {
			p.URL = s.downloadURL(c.FileID, c.Mime)
		}
		s.hub.SendToDevice(client.DeviceID, model.Envelope{
			Type:    model.TypeClip,
			MsgID:   c.MsgID,
			TS:      c.CreatedAt,
			Payload: p,
		})
	}
}

// broadcastDeviceList 广播在线设备列表变化
func (s *Server) broadcastDeviceList(excludeDevice string) {
	s.hub.Broadcast(model.Envelope{
		Type:    model.TypeDeviceList,
		MsgID:   newID(),
		TS:      time.Now().UnixMilli(),
		Payload: s.hub.OnlineDevices(),
	}, excludeDevice)
}

// matchesFilter 判断文本是否命中敏感内容规则
func (s *Server) matchesFilter(text string) bool {
	for _, re := range s.filters {
		if re.MatchString(text) {
			return true
		}
	}
	return false
}

// ---------- 工具 ----------

// remarshal 把 any 重新序列化后解析为目标结构。
// envelope.Payload 是 any，直接断言成 map 再取值容易出错，走一次 JSON 最稳。
func remarshal(v any, dst any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, dst)
}

func ackEnvelope(msgID, status string, code int, reason string) model.Envelope {
	return model.Envelope{
		Type:  model.TypeAck,
		MsgID: msgID,
		TS:    time.Now().UnixMilli(),
		Payload: model.AckPayload{
			MsgID:  msgID,
			Status: status,
			Code:   code,
			Reason: reason,
		},
	}
}

func errorEnvelope(code int, msg string) model.Envelope {
	return model.Envelope{
		Type:    model.TypeError,
		MsgID:   newID(),
		TS:      time.Now().UnixMilli(),
		Payload: model.ErrorPayload{Code: code, Message: msg},
	}
}

// newID 生成随机 16 字节十六进制 ID。
// 服务端生成的 ID 只用于内部消息，正常业务消息的 ID 由客户端提供。
func newID() string {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("%016x", time.Now().UnixNano())
	}
	return hex.EncodeToString(buf)
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}
