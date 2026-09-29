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

// DefaultPairCodeLength 是自动生成配对码时的默认位数。
// 8 位 = 1 亿种组合，配合 /api/pair 的按 IP 限流（10 次/分钟），
// 穷举一遍要约 19 年 —— 这正是"配对码可以长期有效"的前提。
const DefaultPairCodeLength = 8

// PairCode 是当前的配对码及其有效期。
//
// ExpiresAt 为零值表示【永不过期】（配置了固定码时就是这种情况）。
// 有效期内可被多台设备重复使用，防爆破由 api 层的按 IP 限流负责。
type PairCode struct {
	Code      string
	ExpiresAt time.Time
}

// expired 判断配对码是否已失效。零值 ExpiresAt 视为永不过期。
func (p *PairCode) expired(now time.Time) bool {
	return !p.ExpiresAt.IsZero() && now.After(p.ExpiresAt)
}

// IsPermanent 报告当前配对码是否永不过期（用于日志措辞）。
func (p *PairCode) IsPermanent() bool {
	return p != nil && p.ExpiresAt.IsZero()
}

// PairCodePolicy 决定配对码从哪里来。
type PairCodePolicy struct {
	// FixedCode 非空时直接用它：不随机生成、不设过期时间（永久有效）。
	// 用途：多台设备（手机、平板、笔记本）随时配对，不必每次去日志里翻码，
	// 也不必为了加一台设备而重启服务端。
	//
	// 代价：这个码一旦泄露就长期有效，所以【必须】配合按来源 IP 的防爆破限流。
	// 要换码：改配置后重启服务端。
	FixedCode string

	// Length 是自动生成时的位数；<=0 时取 DefaultPairCodeLength。
	Length int

	// TTL 是自动生成码的有效期；<=0 表示永不过期。
	// 配置了 FixedCode 时忽略本字段。
	TTL time.Duration
}

// Manager 负责配对码与 Token 的签发校验。
// Token 采用 HMAC-SHA256 签名，格式为 base64url(deviceID).hex(签名)，
// 避免引入重量级 JWT 依赖，同时保留“带过期时间 + 可校验”的能力。
type Manager struct {
	secret   []byte
	tokenTTL time.Duration
	policy   PairCodePolicy

	mu       sync.RWMutex
	pairCode *PairCode
}

// NewManager 创建鉴权管理器
func NewManager(secret string, policy PairCodePolicy, tokenTTL time.Duration) *Manager {
	if policy.Length <= 0 {
		policy.Length = DefaultPairCodeLength
	}
	m := &Manager{
		secret:   []byte(secret),
		tokenTTL: tokenTTL,
		policy:   policy,
	}
	m.RotatePairCode()
	return m
}

// RotatePairCode 重新确定当前配对码并返回。
//
// 配置了 FixedCode 时返回的就是那个固定码（它本身即永久有效，无"轮换"概念，
// 要换码请改配置后重启）；否则随机生成一个新码。
func (m *Manager) RotatePairCode() string {
	code := m.policy.FixedCode
	var expiresAt time.Time
	if code == "" {
		code = randomDigits(m.policy.Length)
		if m.policy.TTL > 0 {
			expiresAt = time.Now().Add(m.policy.TTL)
		}
	}
	m.mu.Lock()
	m.pairCode = &PairCode{Code: code, ExpiresAt: expiresAt}
	m.mu.Unlock()
	return code
}

// CurrentPairCode 返回当前有效的配对码与有效期。
// 永不过期的码返回零值时间，调用方需据此调整展示措辞。
func (m *Manager) CurrentPairCode() (string, time.Time) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.pairCode == nil || m.pairCode.expired(time.Now()) {
		return "", time.Time{}
	}
	return m.pairCode.Code, m.pairCode.ExpiresAt
}

// VerifyPairCode 校验配对码。
//
// consume=true 时校验通过即作废该码（一次性语义）；
// consume=false 时在有效期内可反复使用 —— 多台设备（手机、平板、笔记本）
// 用同一个码依次配对，不必每加一台就重启服务端。
//
// 选 false 的调用方必须自己做防爆破限流：配对码只有 6 位数字，
// 可重复使用会把暴力枚举窗口拉长到整个有效期。
func (m *Manager) VerifyPairCode(code string, consume bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.pairCode == nil {
		return ErrInvalidPairCode
	}
	if m.pairCode.expired(time.Now()) {
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
