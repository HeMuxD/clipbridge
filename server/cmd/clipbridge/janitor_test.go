package main

import (
	"testing"
	"time"
)

// 清理节奏是"每周一 03:00"，这两个函数算的就是它。
// 周边界特别容易写错（Weekday 是周日=0，而我们要的是周一为一周之始），
// 所以把几种日子都钉一遍。
//
// 参照：2026-10-12 是周一，10-14 周三，10-18 周日，10-19 下周一。
func day(y int, m time.Month, d, hh, mm int) time.Time {
	return time.Date(y, m, d, hh, mm, 0, 0, time.Local)
}

func TestStartOfWeek(t *testing.T) {
	cases := []struct {
		name string
		at   time.Time
		want time.Time
	}{
		{"周一下午", day(2026, 10, 12, 14, 37), day(2026, 10, 12, 0, 0)},
		{"周一凌晨", day(2026, 10, 12, 1, 0), day(2026, 10, 12, 0, 0)},
		{"周一零点整", day(2026, 10, 12, 0, 0), day(2026, 10, 12, 0, 0)},
		{"周三", day(2026, 10, 14, 9, 0), day(2026, 10, 12, 0, 0)},
		{"周日深夜", day(2026, 10, 18, 23, 59), day(2026, 10, 12, 0, 0)},
		{"下周一", day(2026, 10, 19, 3, 30), day(2026, 10, 19, 0, 0)},
	}

	for _, c := range cases {
		got := startOfWeek(c.at)
		if !got.Equal(c.want) {
			t.Errorf("%s: startOfWeek(%s) = %s，期望 %s",
				c.name, c.at.Format("01-02 15:04"), got.Format("01-02 15:04"), c.want.Format("01-02 15:04"))
		}
	}
}

func TestUntilNextCleanup(t *testing.T) {
	cases := []struct {
		name string
		at   time.Time
		want time.Time // 期望的"下一次清理时刻"
	}{
		{"周一下午 → 下周一 03:00", day(2026, 10, 12, 14, 37), day(2026, 10, 19, 3, 0)},
		{"周一 02:00 → 今天 03:00", day(2026, 10, 12, 2, 0), day(2026, 10, 12, 3, 0)},
		{"周一 00:00 → 今天 03:00", day(2026, 10, 12, 0, 0), day(2026, 10, 12, 3, 0)},
		{"周一 03:00 整 → 下周一（不能是 0）", day(2026, 10, 12, 3, 0), day(2026, 10, 19, 3, 0)},
		{"周一 03:01 → 下周一", day(2026, 10, 12, 3, 1), day(2026, 10, 19, 3, 0)},
		{"周三 → 下周一 03:00", day(2026, 10, 14, 9, 0), day(2026, 10, 19, 3, 0)},
		{"周日 23:00 → 明早 03:00", day(2026, 10, 18, 23, 0), day(2026, 10, 19, 3, 0)},
	}

	for _, c := range cases {
		got := c.at.Add(untilNextCleanup(c.at))
		if !got.Equal(c.want) {
			t.Errorf("%s: untilNextCleanup(%s) 落在 %s，期望 %s",
				c.name, c.at.Format("01-02 15:04"), got.Format("01-02 15:04"), c.want.Format("01-02 15:04"))
		}
		if d := untilNextCleanup(c.at); d <= 0 {
			t.Errorf("%s: 时长必须为正，实际 %v", c.name, d)
		}
	}
}

// 「清掉除今天 00:00–03:00 之外的所有缓存」在周一 03:00 触发时，
// 等价于「删掉 created_at 早于本周一 00:00 的记录」——
// cutoff 就是前者那个边界，这里把它钉住。
func TestCleanupCutoffIsStartOfWeek(t *testing.T) {
	at := day(2026, 10, 12, 3, 0) // 正好是触发时刻
	cutoff := startOfWeek(at)

	// 昨天 23:59 的（上周的）要被删
	if !day(2026, 10, 11, 23, 59).Before(cutoff) {
		t.Error("上周日 23:59 的内容应当在 cutoff 之前（会被删）")
	}
	// 今天 00:00 与 02:59 的（保留窗口内）不能被删
	if day(2026, 10, 12, 0, 0).Before(cutoff) {
		t.Error("今天 00:00 的内容不应被删")
	}
	if day(2026, 10, 12, 2, 59).Before(cutoff) {
		t.Error("今天 02:59 的内容不应被删")
	}
}
