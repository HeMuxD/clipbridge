package model

// 消息类型常量，必须与 shared/protocol/PROTOCOL.md 保持一致
const (
	TypeHello      = "hello"
	TypeClip       = "clip"
	TypeAck        = "ack"
	TypePing       = "ping"
	TypePong       = "pong"
	TypeDeviceList = "device_list"
	TypeError      = "error"
)

// 内容类型
const (
	KindText  = "text"
	KindImage = "image"
)

// 内容来源
const (
	OriginClipboard  = "clipboard"
	OriginScreenshot = "screenshot"
	OriginShare      = "share"
	OriginManual     = "manual"
)

// 错误码，必须与 PROTOCOL.md 第 7 节一致
const (
	ErrInvalidToken    = 1001
	ErrDeviceNotPaired = 1002
	ErrRateLimited     = 2001
	ErrFileTooLarge    = 2002
	ErrDuplicate       = 2003
	ErrInternal        = 5001
)

// Envelope 是所有 WebSocket 消息的统一信封
type Envelope struct {
	Type    string `json:"type"`
	MsgID   string `json:"msgId"`
	TS      int64  `json:"ts"`
	Payload any    `json:"payload,omitempty"`
}

// ClipPayload 是 clip 消息的消息体
type ClipPayload struct {
	Kind      string `json:"kind"`
	Hash      string `json:"hash"`
	Origin    string `json:"origin,omitempty"`
	Text      string `json:"text,omitempty"`
	FileID    string `json:"fileId,omitempty"`
	URL       string `json:"url,omitempty"`
	Mime      string `json:"mime,omitempty"`
	Size      int64  `json:"size,omitempty"`
	Width     int    `json:"width,omitempty"`
	Height    int    `json:"height,omitempty"`
	SrcDevice string `json:"srcDevice,omitempty"`
	SrcName   string `json:"srcName,omitempty"`
}

// HelloPayload 是连接建立后的自我介绍
type HelloPayload struct {
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	Platform   string `json:"platform"`
}

// AckPayload 确认收到某条消息
type AckPayload struct {
	MsgID  string `json:"msgId"`
	Status string `json:"status"` // ok | error
	Code   int    `json:"code,omitempty"`
	Reason string `json:"reason,omitempty"`
}

// DeviceInfo 在线设备信息
type DeviceInfo struct {
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	Platform   string `json:"platform"`
	Online     bool   `json:"online"`
}

// ErrorPayload 错误通知
type ErrorPayload struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

// Clip 是一条同步内容在数据库中的映射
type Clip struct {
	ID        int64
	MsgID     string
	Hash      string
	Kind      string
	Origin    string
	SrcDevice string
	SrcName   string
	Text      string
	FileID    string
	Mime      string
	Size      int64
	Width     int
	Height    int
	CreatedAt int64
	ExpiresAt int64
}

// Device 是一台已配对设备
type Device struct {
	DeviceID   string
	DeviceName string
	Platform   string
	TokenHash  string
	PairedAt   int64
	LastSeenAt int64
	Revoked    bool
}
