// Command clipbridge 是 ClipBridge 的同步服务端。
//
// 启动后会：
//  1. 加载配置、打开数据库
//  2. 准备配对码（配置了固定码就用它，否则随机生成）并打印到日志
//  3. 监听 HTTP/WebSocket，等待客户端接入
//  4. 后台维护：每周一 03:00 清理历史缓存，并持续盯磁盘占用上限
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/clipbridge/server/internal/api"
	"github.com/clipbridge/server/internal/auth"
	"github.com/clipbridge/server/internal/buildinfo"
	"github.com/clipbridge/server/internal/config"
	"github.com/clipbridge/server/internal/hub"
	"github.com/clipbridge/server/internal/store"
)

func main() {
	var (
		cfgPath    = flag.String("config", "config.yaml", "配置文件路径")
		showVer    = flag.Bool("version", false, "打印版本号并退出")
		rotateCode = flag.Bool("rotate-code", false,
			"仅打印一个新的配对码并退出。注意：配对码是运行中进程的内存状态，"+
				"独立进程生成的码对正在运行的服务无效；要真正换码请重启服务进程")
	)
	flag.Parse()

	if *showVer {
		fmt.Printf("clipbridge %s\n", buildinfo.Version)
		return
	}

	log := newLogger("info")
	if err := run(*cfgPath, *rotateCode, log); err != nil {
		log.Error("服务启动失败", "err", err)
		os.Exit(1)
	}
}

func run(cfgPath string, rotateOnly bool, baseLog *slog.Logger) error {
	cfg, err := config.Load(cfgPath)
	if err != nil {
		return err
	}

	log := newLogger(cfg.Log.Level)
	log.Info("ClipBridge 服务端启动中",
		"version", buildinfo.Version, "listen", cfg.Server.Listen,
		"publicBaseUrl", cfg.Server.PublicBaseURL)

	// 生产环境安全提示：非 HTTPS 在 Android 9+ 上会被系统直接拒绝
	if !cfg.IsTLS() {
		log.Warn("public_base_url 未使用 HTTPS —— Android 9+ 默认禁止明文连接，" +
			"请配置域名与证书后改为 https://")
	}

	st, err := store.Open(cfg.Storage.DBPath, cfg.Storage.FileDir, cfg.Storage.RetentionDays)
	if err != nil {
		return err
	}
	defer st.Close()

	am := auth.NewManager(
		cfg.Auth.JWTSecret,
		auth.PairCodePolicy{
			FixedCode: cfg.Auth.PairCode,
			Length:    cfg.Auth.PairCodeLength,
			TTL:       time.Duration(cfg.Auth.PairCodeTTL) * time.Second,
		},
		time.Duration(cfg.Auth.TokenTTLDays)*24*time.Hour,
	)

	code, expiresAt := am.CurrentPairCode()
	if rotateOnly {
		if expiresAt.IsZero() {
			fmt.Printf("当前配对码：%s（配置的固定码，永不过期）\n", code)
		} else {
			fmt.Printf("当前配对码：%s（有效至 %s）\n", code, expiresAt.Format("2006-01-02 15:04:05"))
		}
		return nil
	}

	printPairCodeBanner(code, expiresAt)

	h := hub.New(log)
	srv := api.New(cfg, st, am, h, log)

	httpSrv := &http.Server{
		Addr:              cfg.Server.Listen,
		Handler:           srv.Routes(),
		ReadHeaderTimeout: 10 * time.Second,
		// 注意：不能设置 WriteTimeout，否则会掐断 WebSocket 长连接
		IdleTimeout: 120 * time.Second,
	}

	// 优雅退出
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// 后台清理任务
	go janitor(ctx, st, cfg, log)

	errCh := make(chan error, 1)
	go func() {
		log.Info("监听中", "addr", cfg.Server.Listen)
		if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
		log.Info("收到退出信号，正在关闭…")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return httpSrv.Shutdown(shutdownCtx)
}

// 磁盘上限的检查频率。
//
// 它和"每周清一次历史缓存"是两件事：这个是防止写满的安全阀，不能跟着降到
// 一周一次 —— 否则磁盘在周中涨满，要一直撑到下周一才有人管。
const diskCheckInterval = 30 * time.Minute

// janitor 负责两类后台维护：
//
//  1. **历史缓存：每周一 03:00 清一次**。删掉"本周一 00:00 之前"的全部内容，
//     也就是除今天 00:00–03:00 之外的所有缓存 —— 只留下本周刚产生的那点。
//     保留粒度本来就是"周"，没必要隔几分钟就去扫一次库。
//  2. **磁盘上限：每 30 分钟查一次**，超限就按最旧优先删到上限以内。
func janitor(ctx context.Context, st *store.Store, cfg *config.Config, log *slog.Logger) {
	cleanup := time.NewTimer(untilNextCleanup(time.Now()))
	defer cleanup.Stop()

	disk := time.NewTicker(diskCheckInterval)
	defer disk.Stop()

	for {
		select {
		case <-ctx.Done():
			return

		case <-cleanup.C:
			now := time.Now()
			cutoff := startOfWeek(now)
			n, err := st.CleanupBefore(cutoff.UnixMilli())
			if err != nil {
				log.Error("清理历史缓存失败", "err", err)
			} else {
				log.Info("已清理历史缓存（只保留今天 00:00 之后的）",
					"count", n, "before", cutoff.Format("2006-01-02 15:04"))
			}
			cleanup.Reset(untilNextCleanup(now))

		case <-disk.C:
			enforceDiskLimit(st, cfg, log)
		}
	}
}

// startOfWeek 返回"本周一 00:00"（本地时区）
func startOfWeek(now time.Time) time.Time {
	offset := (int(now.Weekday()) + 6) % 7 // 周一 = 0 … 周日 = 6
	d := now.AddDate(0, 0, -offset)
	return time.Date(d.Year(), d.Month(), d.Day(), 0, 0, 0, 0, now.Location())
}

// untilNextCleanup 返回距离下一个"周一 03:00"的时长
func untilNextCleanup(now time.Time) time.Duration {
	next := startOfWeek(now).Add(3 * time.Hour) // 本周一 03:00
	if !next.After(now) {
		next = next.AddDate(0, 0, 7) // 本周的已经过了，等下周一
	}
	return next.Sub(now)
}

// enforceDiskLimit 磁盘占用超过上限时按最旧优先删到上限以内
func enforceDiskLimit(st *store.Store, cfg *config.Config, log *slog.Logger) {
	if cfg.Storage.MaxDiskUsage <= 0 {
		return
	}

	usage, err := st.DiskUsage()
	if err != nil || usage <= cfg.Storage.MaxDiskUsage {
		return
	}

	log.Warn("磁盘占用超过上限，按最旧优先清理",
		"usageMB", usage>>20, "limitMB", cfg.Storage.MaxDiskUsage>>20)

	if n, err := st.TrimToDiskLimit(cfg.Storage.MaxDiskUsage); err != nil {
		log.Error("按最旧优先清理失败", "err", err)
	} else if n > 0 {
		log.Info("已按最旧优先清理", "count", n)
	}
}

func newLogger(level string) *slog.Logger {
	var lv slog.Level
	switch level {
	case "debug":
		lv = slog.LevelDebug
	case "warn":
		lv = slog.LevelWarn
	case "error":
		lv = slog.LevelError
	default:
		lv = slog.LevelInfo
	}
	return slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: lv}))
}

// printPairCodeBanner 醒目地打印配对码，方便用户从日志里一眼找到
func printPairCodeBanner(code string, expiresAt time.Time) {
	valid := "有效至 " + expiresAt.Format("2006-01-02 15:04:05")
	if expiresAt.IsZero() {
		valid = "固定码，永久有效（可多台设备重复使用）"
	}
	banner := `
============================================================
                    配对码（可多台设备重复使用）
                        %s
                 %s
============================================================
`
	fmt.Printf(banner, code, valid)
	fmt.Println()
}
