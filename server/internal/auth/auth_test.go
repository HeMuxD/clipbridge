package auth

import (
	"strings"
	"testing"
	"time"
)

// shortTTL 是测试里常用的配对码有效期
func shortTTL() PairCodePolicy { return PairCodePolicy{TTL: time.Minute} }

// TestIssueAndVerifyToken 是最关键的一条回归测试。
// 曾经 VerifyToken 用 fmt.Sscanf(raw, "%s|%d") 解析 payload，
// 而 %s 会一直读到空白符为止，把 `|` 与时间戳整段吞掉，
// 导致所有自己签发的 Token 都验不过 —— 整套鉴权等于失效。
func TestIssueAndVerifyToken(t *testing.T) {
	m := NewManager("test-secret", shortTTL(), 24*time.Hour)

	for _, deviceID := range []string{"win-desktop-01", "android-pixel8", "win.desktop.01", "a"} {
		token, expiresAt := m.IssueToken(deviceID)
		if !expiresAt.After(time.Now()) {
			t.Fatalf("设备 %q 的过期时间应在将来", deviceID)
		}

		got, err := m.VerifyToken(token)
		if err != nil {
			t.Fatalf("校验自己签发的 Token（设备 %q）失败: %v", deviceID, err)
		}
		if got != deviceID {
			t.Fatalf("设备 ID 不一致：得到 %q，期望 %q", got, deviceID)
		}
	}
}

func TestVerifyRejectsInvalidToken(t *testing.T) {
	m := NewManager("test-secret", shortTTL(), time.Hour)
	token, _ := m.IssueToken("dev-1")

	// 换了密钥就应当验不过
	other := NewManager("another-secret", shortTTL(), time.Hour)
	if _, err := other.VerifyToken(token); err == nil {
		t.Fatal("换密钥后不应校验通过")
	}

	// 篡改签名
	if _, err := m.VerifyToken(token + "aa"); err == nil {
		t.Fatal("签名被篡改不应通过")
	}

	// 结构非法的输入
	for _, bad := range []string{"", "abc", "abc.", ".abc", "zzzz.aabb"} {
		if _, err := m.VerifyToken(bad); err == nil {
			t.Fatalf("非法 Token %q 不应通过", bad)
		}
	}
}

func TestVerifyRejectsExpiredToken(t *testing.T) {
	// TTL 取负值，签出来即是已过期
	m := NewManager("test-secret", shortTTL(), -time.Hour)
	token, _ := m.IssueToken("dev-1")

	if _, err := m.VerifyToken(token); err == nil {
		t.Fatal("过期 Token 不应通过")
	}
}

// TestPairCodeDefaultLength 确认默认长度是 8 位。
// 6 位（100 万种）配合"长期有效"不够安全，8 位（1 亿种）才撑得住。
func TestPairCodeDefaultLength(t *testing.T) {
	m := NewManager("test-secret", shortTTL(), time.Hour)

	code, _ := m.CurrentPairCode()
	if len(code) != DefaultPairCodeLength {
		t.Fatalf("默认配对码应为 %d 位，实际 %q（%d 位）",
			DefaultPairCodeLength, code, len(code))
	}
	if strings.Trim(code, "0123456789") != "" {
		t.Fatalf("配对码应全为数字，实际 %q", code)
	}
}

// TestPairCodeReusableWithinTTL 是本次修复的核心回归测试。
//
// 曾经的语义是"配对成功即作废"，导致第二台设备必然配不上，
// 用户只能重启服务端才能拿到新码 —— 多设备用户每次都要踩一次。
func TestPairCodeReusableWithinTTL(t *testing.T) {
	m := NewManager("test-secret", shortTTL(), time.Hour)
	code, _ := m.CurrentPairCode()

	// 三台设备依次用同一个码配对，都应成功
	for _, dev := range []string{"phone-a", "phone-b", "windows-pc"} {
		if err := m.VerifyPairCode(code, false); err != nil {
			t.Fatalf("设备 %s 用同一个配对码配对失败: %v", dev, err)
		}
	}

	// 配对之后码仍然有效，且还能被读出来展示
	after, _ := m.CurrentPairCode()
	if after != code {
		t.Fatalf("重复配对后配对码不应变化：期望 %q，实际 %q", code, after)
	}
}

// TestExplicitConsumeStillWorks 保留一次性语义的显式能力（consume=true）
func TestExplicitConsumeStillWorks(t *testing.T) {
	m := NewManager("test-secret", shortTTL(), time.Hour)
	code, _ := m.CurrentPairCode()

	if err := m.VerifyPairCode(code, true); err != nil {
		t.Fatalf("首次使用配对码应当成功: %v", err)
	}
	if err := m.VerifyPairCode(code, true); err == nil {
		t.Fatal("consume=true 时第二次使用应当失败")
	}
	if c, _ := m.CurrentPairCode(); c != "" {
		t.Fatalf("配对码已被消费，不应再返回：%q", c)
	}
}

// TestFixedPairCodeIsPermanent 确认固定码：原样使用、不过期、可重复使用。
func TestFixedPairCodeIsPermanent(t *testing.T) {
	const fixed = "13572468"
	m := NewManager("test-secret", PairCodePolicy{
		FixedCode: fixed,
		Length:    DefaultPairCodeLength,
		TTL:       time.Minute, // 配了 FixedCode 时应当被忽略
	}, time.Hour)

	code, expiresAt := m.CurrentPairCode()
	if code != fixed {
		t.Fatalf("应使用配置的固定码 %q，实际 %q", fixed, code)
	}
	if !expiresAt.IsZero() {
		t.Fatalf("固定码不应有过期时间，实际 %v", expiresAt)
	}

	for i := 0; i < 3; i++ {
		if err := m.VerifyPairCode(fixed, false); err != nil {
			t.Fatalf("第 %d 次使用固定码应当成功: %v", i+1, err)
		}
	}

	// 重新"轮换"也不该改变固定码
	if got := m.RotatePairCode(); got != fixed {
		t.Fatalf("固定码不应被轮换掉：期望 %q，实际 %q", fixed, got)
	}
}

// TestPairCodeRejectsWrongInput 确认错误的码不会被放行
func TestPairCodeRejectsWrongInput(t *testing.T) {
	m := NewManager("test-secret", shortTTL(), time.Hour)

	wrong := "00000000"
	if code, _ := m.CurrentPairCode(); code == wrong {
		wrong = "11111111"
	}
	if err := m.VerifyPairCode(wrong, false); err == nil {
		t.Fatal("错误的配对码不应通过")
	}
	// 长度不同也不应通过
	if err := m.VerifyPairCode("123", false); err == nil {
		t.Fatal("长度不符的配对码不应通过")
	}
}

// TestPairCodeExpires 验证正数 TTL 会真的过期。
//
// 注意语义变更：旧实现里 TTL=0 表示"立刻过期"，现在 <=0 统一表示"永不过期"
// （与 project 里 max_disk_usage: 0 = 无限制 的约定一致），
// 所以要测过期必须给一个正数 TTL。
func TestPairCodeExpires(t *testing.T) {
	m := NewManager("test-secret", PairCodePolicy{TTL: time.Millisecond}, time.Hour)
	code := m.RotatePairCode()

	time.Sleep(5 * time.Millisecond)

	if c, _ := m.CurrentPairCode(); c != "" {
		t.Fatalf("过期后不应返回配对码，实际 %q", c)
	}
	if err := m.VerifyPairCode(code, false); err == nil {
		t.Fatal("过期的配对码不应通过")
	}
}

// TestPairCodeZeroTTLNeverExpires 确认 TTL<=0 时自动生成的码也永不过期，
// 且不再带 ExpiresAt（调用方据此调整展示措辞）。
func TestPairCodeZeroTTLNeverExpires(t *testing.T) {
	for _, ttl := range []time.Duration{0, -time.Second} {
		m := NewManager("test-secret", PairCodePolicy{TTL: ttl}, time.Hour)

		code, expiresAt := m.CurrentPairCode()
		if code == "" {
			t.Fatalf("TTL=%v 时应仍返回配对码", ttl)
		}
		if !expiresAt.IsZero() {
			t.Fatalf("TTL=%v 时应视为永不过期（零值时间），实际 %v", ttl, expiresAt)
		}
		if len(code) != DefaultPairCodeLength {
			t.Fatalf("TTL=%v 时配对码应为 %d 位，实际 %q", ttl, DefaultPairCodeLength, code)
		}
	}
}
