// Command clipbridge 是 ClipBridge 的同步服务端。
//
// 启动后会：
//  1. 加载配置、打开数据库
//  2. 生成一个一次性配对码并打印到日志
//  3. 监听 HTTP/WebSocket，等待客户端接入
//  4. 后台定期清理过期内容与磁盘占用
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
	"github.com/clipbridge/server/internal/config"
	"github.com/clipbridge/server/internal/hub"
	"github.com/clipbridge/server/internal/store"
)

var version = "1.0.0"

func main() {
	var (
		cfgPath    = flag.String("config", "config.yaml", "配置文件路径")
		showVer    = flag.Bool("version", false, "打印版本号并退出")
		rotateCode = flag.Bool("rotate-code", false, "仅生成一个新的配对码并退出")
	)
	flag.Parse()

	if *showVer {
		fmt.Printf("clipbridge %s\n", version)
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
		"version", version, "listen", cfg.Server.Listen,
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
		time.Duration(cfg.Auth.PairCodeTTL)*time.Second,
		time.Duration(cfg.Auth.TokenTTLDays)*24*time.Hour,
	)

	code, expiresAt := am.CurrentPairCode()
	if rotateOnly {
		fmt.Printf("新的配对码：%s（有效至 %s）\n",
			code, expiresAt.Format("2006-01-02 15:04:05"))
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

// janitor 定期清理过期内容，并在磁盘超限时按最旧优先删除
func janitor(ctx context.Context, st *store.Store, cfg *config.Config, log *slog.Logger) {
	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if n, err := st.CleanupExpired(); err != nil {
				log.Error("清理过期内容失败", "err", err)
			} else if n > 0 {
				log.Info("已清理过期内容", "count", n)
			}

			if cfg.Storage.MaxDiskUsage > 0 {
				usage, err := st.DiskUsage()
				if err == nil && usage > cfg.Storage.MaxDiskUsage {
					log.Warn("磁盘占用超过上限，将触发清理",
						"usageMB", usage>>20, "limitMB", cfg.Storage.MaxDiskUsage>>20)
					// 立即清一轮过期内容；若仍超限，需要人工介入或调小保留天数
					_, _ = st.CleanupExpired()
				}
			}
		}
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
	banner := `
============================================================
                    配对码（首次配对使用）
                        %s
              有效至 %s
============================================================
`
	fmt.Printf(banner, code, expiresAt.Format("2006-01-02 15:04:05"))
	fmt.Println()
}
