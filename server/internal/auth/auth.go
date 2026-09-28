package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"math/big"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	// ErrInvalidPairCode 配对码错误或已过期
	ErrInvalidPairCode = errors.New("配对码无效或已过期")
	// ErrInvalidToken Token 无效或已过期
	ErrInvalidToken = errors.New("Token 无效或已过期")
)

// PairCode 是一次性配对码及其有效期。
// 服务端启动时生成一个，客户端首次配对时使用。
type PairCode struct {
	Code      string
	ExpiresAt time.Time
}

// Manager 负责配对码与 Token 的签发校验。
// Token 采用 HMAC-SHA256 签名，格式为 base64url(deviceID).hex(签名)，
// 避免引入重量级 JWT 依赖，同时保留“带过期时间 + 可校验”的能力。
type Manager struct {
	secret      []byte
	tokenTTL    time.Duration
	pairCodeTTL time.Duration

	mu       sync.RWMutex
	pairCode *PairCode
}

// NewManager 创建鉴权管理器
func NewManager(secret string, pairCodeTTL, tokenTTL time.Duration) *Manager {
	m := &Manager{
		secret:      []byte(secret),
		tokenTTL:    tokenTTL,
		pairCodeTTL: pairCodeTTL,
	}
	m.RotatePairCode()
	return m
}

// RotatePairCode 生成新的 6 位配对码，旧码立即失效
func (m *Manager) RotatePairCode() string {
	code := randomDigits(6)
	m.mu.Lock()
	m.pairCode = &PairCode{
		Code:      code,
		ExpiresAt: time.Now().Add(m.pairCodeTTL),
	}
	m.mu.Unlock()
	return code
}

// CurrentPairCode 返回当前有效的配对码，若已过期则返回空字符串
func (m *Manager) CurrentPairCode() (string, time.Time) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.pairCode == nil || time.Now().After(m.pairCode.ExpiresAt) {
		return "", time.Time{}
	}
	return m.pairCode.Code, m.pairCode.ExpiresAt
}

// VerifyPairCode 校验配对码。成功后可选择是否立即作废该码。
func (m *Manager) VerifyPairCode(code string, consume bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.pairCode == nil {
		return ErrInvalidPairCode
	}
	if time.Now().After(m.pairCode.ExpiresAt) {
		return ErrInvalidPairCode
	}
	// 常量时间比较，避免时序侧信道
	if !hmac.Equal([]byte(code), []byte(m.pairCode.Code)) {
		return ErrInvalidPairCode
	}
	if consume {
		m.pairCode = nil
	}
	return nil
}

// IssueToken 为设备签发长期 Token
func (m *Manager) IssueToken(deviceID string) (string, time.Time) {
	expiresAt := time.Now().Add(m.tokenTTL)
	// 签名内容：deviceID + 过期时间戳，防止 Token 被无限期复用
	msg := fmt.Sprintf("%s|%d", deviceID, expiresAt.Unix())
	sig := m.sign(msg)
	return hex.EncodeToString([]byte(msg)) + "." + sig, expiresAt
}

// VerifyToken 校验 Token 并返回设备 ID
func (m *Manager) VerifyToken(token string) (string, error) {
	// 拆出 payload 与签名，格式：hex(payload) + "." + hex(HMAC)
	dot := strings.LastIndex(token, ".")
	if dot <= 0 || dot == len(token)-1 {
		return "", ErrInvalidToken
	}
	payloadHex, sig := token[:dot], token[dot+1:]

	raw, err := hex.DecodeString(payloadHex)
	if err != nil {
		return "", ErrInvalidToken
	}

	// payload 形如 "deviceID|过期时间戳"。
	// 注意：不能用 fmt.Sscanf(raw, "%s|%d")——%s 会一直读到空白字符为止，
	// 把 `|` 和后面的时间戳一并吞掉，解析必然失败，导致所有 Token 都验不过。
	// 按最后一个 `|` 手工切分才是可靠的。
	sep := strings.LastIndex(string(raw), "|")
	if sep <= 0 || sep == len(raw)-1 {
		return "", ErrInvalidToken
	}
	deviceID := string(raw[:sep])
	expiresUnix, err := strconv.ParseInt(string(raw[sep+1:]), 10, 64)
	if err != nil {
		return "", ErrInvalidToken
	}

	// 先验签，再验期。顺序不可颠倒，否则存在伪造风险。
	if !hmac.Equal([]byte(sig), []byte(m.sign(string(raw)))) {
		return "", ErrInvalidToken
	}
	if time.Now().Unix() > expiresUnix {
		return "", ErrInvalidToken
	}
	return deviceID, nil
}

// HashToken 返回 Token 的 SHA-256，用于入库存储（不存明文）
func HashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// sign 用 HMAC-SHA256 对消息签名
func (m *Manager) sign(msg string) string {
	h := hmac.New(sha256.New, m.secret)
	h.Write([]byte(msg))
	return hex.EncodeToString(h.Sum(nil))
}

// randomDigits 生成 n 位随机数字字符串，使用密码学安全随机源
func randomDigits(n int) string {
	max := new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(n)), nil)
	v, err := rand.Int(rand.Reader, max)
	if err != nil {
		// 极端情况下退化为时间戳后缀，保证功能可用
		return fmt.Sprintf("%0*d", n, time.Now().UnixNano()%1e6)[:n]
	}
	return fmt.Sprintf("%0*d", n, v.Int64())
}
