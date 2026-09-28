package api

import (
	"sync"
	"time"
)

// rateLimiter 是每设备固定窗口限流器。
// 用固定窗口而非滑动窗口，是因为同步场景下"每分钟 60 次"这种粗粒度限制
// 不需要精确到毫秒，实现越简单越不容易出 bug。
type rateLimiter struct {
	mu      sync.Mutex
	limit   int
	window  time.Duration
	buckets map[string]*bucket
}

type bucket struct {
	count   int
	resetAt time.Time
}

func newRateLimiter(limitPerMinute int) *rateLimiter {
	if limitPerMinute <= 0 {
		limitPerMinute = 60
	}
	rl := &rateLimiter{
		limit:   limitPerMinute,
		window:  time.Minute,
		buckets: make(map[string]*bucket),
	}
	go rl.gc()
	return rl
}

// Allow 判断某设备本次请求是否放行
func (r *rateLimiter) Allow(key string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()

	now := time.Now()
	b, ok := r.buckets[key]
	if !ok || now.After(b.resetAt) {
		r.buckets[key] = &bucket{count: 1, resetAt: now.Add(r.window)}
		return true
	}
	if b.count >= r.limit {
		return false
	}
	b.count++
	return true
}

// gc 定期清理过期桶，防止 map 无限增长
func (r *rateLimiter) gc() {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		now := time.Now()
		r.mu.Lock()
		for k, b := range r.buckets {
			if now.After(b.resetAt) {
				delete(r.buckets, k)
			}
		}
		r.mu.Unlock()
	}
}
