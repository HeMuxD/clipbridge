package config

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"

	"gopkg.in/yaml.v3"
)

// Config 是服务端的完整配置树，对应 config.example.yaml
type Config struct {
	Server  ServerConfig  `yaml:"server"`
	Storage StorageConfig `yaml:"storage"`
	Auth    AuthConfig    `yaml:"auth"`
	Limits  LimitsConfig  `yaml:"limits"`
	Log     LogConfig     `yaml:"log"`
	Filter  FilterConfig  `yaml:"filter"`
}

type ServerConfig struct {
	Listen        string `yaml:"listen"`
	PublicBaseURL string `yaml:"public_base_url"`
	BehindProxy   bool   `yaml:"behind_proxy"`
}

type StorageConfig struct {
	DBPath             string `yaml:"db_path"`
	FileDir            string `yaml:"file_dir"`
	MaxFileSize        int64  `yaml:"max_file_size"`
	RateLimitPerMinute int    `yaml:"rate_limit_per_minute"`
	RetentionDays      int    `yaml:"retention_days"`
	MaxDiskUsage       int64  `yaml:"max_disk_usage"`
}

type AuthConfig struct {
	PairCodeTTL  int    `yaml:"pair_code_ttl"`
	TokenTTLDays int    `yaml:"token_ttl_days"`
	JWTSecret    string `yaml:"jwt_secret"`
}

type LimitsConfig struct {
	MaxConnPerDevice     int `yaml:"max_conn_per_device"`
	OfflineQueueTTLHours int `yaml:"offline_queue_ttl_hours"`
}

type LogConfig struct {
	Level string `yaml:"level"`
}

type FilterConfig struct {
	Enabled  bool     `yaml:"enabled"`
	Patterns []string `yaml:"patterns"`
}

// Default 返回一份可直接运行的默认配置
func Default() *Config {
	return &Config{
		Server: ServerConfig{
			Listen:        "0.0.0.0:8080",
			PublicBaseURL: "http://127.0.0.1:8080",
			BehindProxy:   false,
		},
		Storage: StorageConfig{
			DBPath:             "./data/clipbridge.db",
			FileDir:            "./data/files",
			MaxFileSize:        20 << 20,
			RateLimitPerMinute: 60,
			RetentionDays:      7,
			MaxDiskUsage:       20 << 30,
		},
		Auth: AuthConfig{
			PairCodeTTL:  600,
			TokenTTLDays: 365,
		},
		Limits: LimitsConfig{
			MaxConnPerDevice:     3,
			OfflineQueueTTLHours: 24,
		},
		Log: LogConfig{Level: "info"},
	}
}

// Load 读取配置文件。文件不存在时使用默认配置。
func Load(path string) (*Config, error) {
	cfg := Default()

	if path != "" {
		data, err := os.ReadFile(path)
		switch {
		case err == nil:
			if err := yaml.Unmarshal(data, cfg); err != nil {
				return nil, fmt.Errorf("解析配置文件失败: %w", err)
			}
		case os.IsNotExist(err):
			// 使用默认配置继续
		default:
			return nil, fmt.Errorf("读取配置文件失败: %w", err)
		}
	}

	// 环境变量覆盖，便于容器化部署
	if v := os.Getenv("CLIPBRIDGE_LISTEN"); v != "" {
		cfg.Server.Listen = v
	}
	if v := os.Getenv("CLIPBRIDGE_PUBLIC_BASE_URL"); v != "" {
		cfg.Server.PublicBaseURL = v
	}
	if v := os.Getenv("CLIPBRIDGE_DB_PATH"); v != "" {
		cfg.Storage.DBPath = v
	}
	if v := os.Getenv("CLIPBRIDGE_FILE_DIR"); v != "" {
		cfg.Storage.FileDir = v
	}
	if v := os.Getenv("CLIPBRIDGE_JWT_SECRET"); v != "" {
		cfg.Auth.JWTSecret = v
	}

	// JWT 密钥留空时随机生成：安全但重启后所有 Token 失效。
	// 生产环境必须显式配置，否则每次重启都要重新配对。
	if cfg.Auth.JWTSecret == "" {
		buf := make([]byte, 32)
		if _, err := rand.Read(buf); err != nil {
			return nil, fmt.Errorf("生成临时 JWT 密钥失败: %w", err)
		}
		cfg.Auth.JWTSecret = hex.EncodeToString(buf)
	}

	if err := cfg.validate(); err != nil {
		return nil, err
	}
	return cfg, nil
}

func (c *Config) validate() error {
	if c.Server.PublicBaseURL == "" {
		return fmt.Errorf("server.public_base_url 不能为空")
	}
	if c.Storage.MaxFileSize <= 0 {
		return fmt.Errorf("storage.max_file_size 必须大于 0")
	}
	if c.Auth.TokenTTLDays <= 0 {
		return fmt.Errorf("auth.token_ttl_days 必须大于 0")
	}
	if c.Limits.MaxConnPerDevice <= 0 {
		c.Limits.MaxConnPerDevice = 3
	}
	return nil
}

// IsTLS 判断对外地址是否为 HTTPS。用于启动时校验生产环境安全性。
func (c *Config) IsTLS() bool {
	return len(c.Server.PublicBaseURL) >= 8 && c.Server.PublicBaseURL[:8] == "https://"
}
