package auth

import (
	"testing"
	"time"
)

// TestIssueAndVerifyToken 是最关键的一条回归测试。
// 曾经 VerifyToken 用 fmt.Sscanf(raw, "%s|%d") 解析 payload，
// 而 %s 会一直读到空白符为止，把 `|` 与时间戳整段吞掉，
// 导致所有自己签发的 Token 都验不过 —— 整套鉴权等于失效。
func TestIssueAndVerifyToken(t *testing.T) {
	m := NewManager("test-secret", time.Minute, 24*time.Hour)

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
	m := NewManager("test-secret", time.Minute, time.Hour)
	token, _ := m.IssueToken("dev-1")

	// 换了密钥就应当验不过
	other := NewManager("another-secret", time.Minute, time.Hour)
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
	m := NewManager("test-secret", time.Minute, -time.Hour)
	token, _ := m.IssueToken("dev-1")

	if _, err := m.VerifyToken(token); err == nil {
		t.Fatal("过期 Token 不应通过")
	}
}

func TestPairCodeIsValidAndOneTime(t *testing.T) {
	m := NewManager("test-secret", time.Minute, time.Hour)

	code, expiresAt := m.CurrentPairCode()
	if len(code) != 6 {
		t.Fatalf("配对码应为 6 位数字，实际得到 %q", code)
	}
	if !expiresAt.After(time.Now()) {
		t.Fatal("配对码过期时间应在将来")
	}

	if err := m.VerifyPairCode(code, true); err != nil {
		t.Fatalf("首次使用配对码应当成功: %v", err)
	}
	// consume=true 之后必须作废
	if err := m.VerifyPairCode(code, true); err == nil {
		t.Fatal("配对码是一次性的，第二次使用应当失败")
	}
	if c, _ := m.CurrentPairCode(); c != "" {
		t.Fatalf("配对码已被消费，不应再返回：%q", c)
	}
}

func TestPairCodeRejectsWrongInput(t *testing.T) {
	m := NewManager("test-secret", time.Minute, time.Hour)

	wrong := "000000"
	if code, _ := m.CurrentPairCode(); code == wrong {
		wrong = "111111"
	}
	if err := m.VerifyPairCode(wrong, false); err == nil {
		t.Fatal("错误的配对码不应通过")
	}
}

func TestPairCodeExpires(t *testing.T) {
	// TTL 取负值，配对码生出来即已过期
	m := NewManager("test-secret", -time.Second, time.Hour)
	code := m.RotatePairCode()

	if c, _ := m.CurrentPairCode(); c != "" {
		t.Fatalf("过期后不应返回配对码，实际 %q", c)
	}
	if err := m.VerifyPairCode(code, false); err == nil {
		t.Fatal("过期的配对码不应通过")
	}
}
