package hub

import (
	"encoding/json"
	"log/slog"
	"sync"
	"time"

	"github.com/clipbridge/server/internal/model"

	"github.com/gorilla/websocket"
)

const (
	// 写超时：超过这个时间还没把消息写出去，说明对端已经不可用
	writeWait = 10 * time.Second
	// 心跳间隔
	pingPeriod = 30 * time.Second
	// 读超时：两次 pong 之间最长等待时间，设为心跳的 2 倍多
	pongWait = 75 * time.Second
	// 每个连接待发送队列的长度，超出说明消费不过来
	sendBufSize = 64
)

// Client 代表一条已建立的 WebSocket 连接
type Client struct {
	DeviceID   string
	DeviceName string
	Platform   string

	hub  *Hub
	conn *websocket.Conn
	send chan []byte

	closeOnce sync.Once
	closed    chan struct{}
}

// Hub 维护所有在线连接，并负责消息广播。
// 所有对 clients 的修改都在 run 这个单 goroutine 里完成，因此不需要额外加锁。
type Hub struct {
	mu sync.RWMutex
	// deviceID -> 该设备的连接集合（同一设备允许多终端登录）
	clients map[string]map[*Client]struct{}

	log *slog.Logger
}

// New 创建 Hub
func New(log *slog.Logger) *Hub {
	return &Hub{
		clients: make(map[string]map[*Client]struct{}),
		log:     log,
	}
}

// register 把连接加入在线表
func (h *Hub) register(c *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if h.clients[c.DeviceID] == nil {
		h.clients[c.DeviceID] = make(map[*Client]struct{})
	}
	h.clients[c.DeviceID][c] = struct{}{}
	h.log.Info("设备上线", "deviceId", c.DeviceID, "name", c.DeviceName,
		"platform", c.Platform, "online", h.countLocked())
}

// unregister 把连接移出在线表
func (h *Hub) unregister(c *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if set, ok := h.clients[c.DeviceID]; ok {
		if _, exists := set[c]; exists {
			delete(set, c)
			close(c.send)
		}
		if len(set) == 0 {
			delete(h.clients, c.DeviceID)
		}
	}
	h.log.Info("设备下线", "deviceId", c.DeviceID, "online", h.countLocked())
}

// IsOnline 判断某设备是否有活跃连接
func (h *Hub) IsOnline(deviceID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients[deviceID]) > 0
}

// Count 返回当前在线连接数
func (h *Hub) Count() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return h.countLocked()
}

func (h *Hub) countLocked() int {
	n := 0
	for _, set := range h.clients {
		n += len(set)
	}
	return n
}

// OnlineDevices 返回去重后的在线设备列表
func (h *Hub) OnlineDevices() []model.DeviceInfo {
	h.mu.RLock()
	defer h.mu.RUnlock()

	seen := make(map[string]bool, len(h.clients))
	out := make([]model.DeviceInfo, 0, len(h.clients))
	for _, set := range h.clients {
		for c := range set {
			if seen[c.DeviceID] {
				continue
			}
			seen[c.DeviceID] = true
			out = append(out, model.DeviceInfo{
				DeviceID:   c.DeviceID,
				DeviceName: c.DeviceName,
				Platform:   c.Platform,
				Online:     true,
			})
		}
	}
	return out
}

// Broadcast 把消息发送给所有在线设备，但排除 excludeDevice。
// msg 会被序列化为 JSON。
func (h *Hub) Broadcast(msg any, excludeDevice string) int {
	data, err := json.Marshal(msg)
	if err != nil {
		h.log.Error("消息序列化失败", "err", err)
		return 0
	}
	return h.broadcastRaw(data, excludeDevice)
}

// SendToDevice 只发送给指定设备的全部连接
func (h *Hub) SendToDevice(deviceID string, msg any) int {
	data, err := json.Marshal(msg)
	if err != nil {
		h.log.Error("消息序列化失败", "err", err)
		return 0
	}

	h.mu.RLock()
	defer h.mu.RUnlock()

	n := 0
	// 复制一份切片再发，避免持锁期间阻塞
	targets := make([]*Client, 0, len(h.clients[deviceID]))
	for c := range h.clients[deviceID] {
		targets = append(targets, c)
	}
	for _, c := range targets {
		if c.trySend(data) {
			n++
		}
	}
	return n
}

func (h *Hub) broadcastRaw(data []byte, excludeDevice string) int {
	h.mu.RLock()
	defer h.mu.RUnlock()

	// 快照目标列表，避免持锁执行发送
	targets := make([]*Client, 0, h.countLocked())
	for deviceID, set := range h.clients {
		if deviceID == excludeDevice {
			continue
		}
		for c := range set {
			targets = append(targets, c)
		}
	}

	n := 0
	for _, c := range targets {
		if c.trySend(data) {
			n++
		}
	}
	return n
}

// trySend 非阻塞发送。队列满时不阻塞广播，直接放弃该连接（对端会靠重连恢复）。
func (c *Client) trySend(data []byte) bool {
	select {
	case <-c.closed:
		return false
	default:
	}

	select {
	case c.send <- data:
		return true
	default:
		// 发送队列已满，说明该客户端消费过慢，主动断开以免拖垮广播
		c.hub.log.Warn("发送队列已满，断开连接", "deviceId", c.DeviceID)
		c.Close()
		return false
	}
}

// Close 幂等地关闭连接
func (c *Client) Close() {
	c.closeOnce.Do(func() {
		close(c.closed)
		_ = c.conn.Close()
	})
}

// ---------- 连接的读写循环 ----------

// NewClient 包装一条 WebSocket 连接
func (h *Hub) NewClient(conn *websocket.Conn, deviceID, deviceName, platform string) *Client {
	return &Client{
		DeviceID:   deviceID,
		DeviceName: deviceName,
		Platform:   platform,
		hub:        h,
		conn:       conn,
		send:       make(chan []byte, sendBufSize),
		closed:     make(chan struct{}),
	}
}

// Serve 启动该连接的读写循环，阻塞直到连接关闭。
// onMessage 在收到每条消息时被调用，返回的消息会被回给该客户端（可为 nil）。
func (h *Hub) Serve(c *Client, onMessage func(*Client, []byte) any, onOpen func(*Client)) {
	h.register(c)
	defer h.unregister(c)

	if onOpen != nil {
		onOpen(c)
	}

	// 写循环：独立 goroutine，负责心跳与出队发送
	go c.writeLoop()

	// 读循环：运行在当前 goroutine
	c.readLoop(onMessage)
}

func (c *Client) readLoop(onMessage func(*Client, []byte) any) {
	_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(pongWait))
	})

	for {
		_, data, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err,
				websocket.CloseGoingAway, websocket.CloseNormalClosure) {
				c.hub.log.Warn("连接异常断开", "deviceId", c.DeviceID, "err", err)
			}
			return
		}

		// 收到任何消息都视为活跃，刷新读超时
		_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))

		if onMessage == nil {
			continue
		}
		if reply := onMessage(c, data); reply != nil {
			if b, err := json.Marshal(reply); err == nil {
				c.trySend(b)
			}
		}
	}
}

func (c *Client) writeLoop() {
	ticker := time.NewTicker(pingPeriod)
	defer ticker.Stop()

	for {
		select {
		case <-c.closed:
			return

		case data, ok := <-c.send:
			if !ok {
				return
			}
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.TextMessage, data); err != nil {
				c.hub.log.Warn("写入失败", "deviceId", c.DeviceID, "err", err)
				c.Close()
				return
			}

		case <-ticker.C:
			// 用 WebSocket 协议级 Ping 探测对端存活
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				c.Close()
				return
			}
		}
	}
}
