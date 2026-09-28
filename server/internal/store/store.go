package store

import (
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/clipbridge/server/internal/model"

	_ "modernc.org/sqlite"
)

var (
	// ErrNotFound 记录不存在
	ErrNotFound = errors.New("记录不存在")
	// ErrDuplicate 内容重复，已被去重
	ErrDuplicate = errors.New("内容重复")
)

// Store 封装数据库与文件存储的全部读写。
// 所有 SQL 都集中在这里，上层不直接接触 *sql.DB。
type Store struct {
	db        *sql.DB
	fileDir   string
	retention time.Duration
}

// Open 打开数据库并确保表结构存在
func Open(dbPath, fileDir string, retentionDays int) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(dbPath), 0o755); err != nil {
		return nil, fmt.Errorf("创建数据库目录失败: %w", err)
	}
	if err := os.MkdirAll(fileDir, 0o755); err != nil {
		return nil, fmt.Errorf("创建文件目录失败: %w", err)
	}

	// 使用 modernc.org/sqlite（纯 Go 驱动，驱动名 "sqlite"）。
	// 通过 DSN 参数开启 busy_timeout 与 WAL，见下方 PRAGMA 注释。
	dsn := dbPath + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("打开数据库失败: %w", err)
	}
	// SQLite 写操作是串行的，限制连接数可避免锁竞争
	db.SetMaxOpenConns(1)

	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("连接数据库失败: %w", err)
	}

	s := &Store{
		db:        db,
		fileDir:   fileDir,
		retention: time.Duration(retentionDays) * 24 * time.Hour,
	}
	if err := s.migrate(); err != nil {
		return nil, err
	}
	return s, nil
}

// Close 关闭数据库
func (s *Store) Close() error { return s.db.Close() }

// migrate 执行 migrations 目录下的建表语句。
// 语句全部使用 IF NOT EXISTS，可重复执行。
func (s *Store) migrate() error {
	schema, err := os.ReadFile("migrations/001_init.sql")
	if err != nil {
		// 允许从任意工作目录启动：退化为内置的最小建表语句
		schema = []byte(fallbackSchema)
	}
	if _, err := s.db.Exec(string(schema)); err != nil {
		return fmt.Errorf("初始化数据库结构失败: %w", err)
	}
	return nil
}

// ---------- 设备 ----------

// UpsertDevice 在配对成功时写入或更新设备记录
func (s *Store) UpsertDevice(d *model.Device) error {
	_, err := s.db.Exec(`
		INSERT INTO devices (device_id, device_name, platform, token_hash, paired_at, last_seen_at, revoked)
		VALUES (?, ?, ?, ?, ?, ?, 0)
		ON CONFLICT(device_id) DO UPDATE SET
			device_name = excluded.device_name,
			platform    = excluded.platform,
			token_hash  = excluded.token_hash,
			last_seen_at = excluded.last_seen_at,
			revoked     = 0`,
		d.DeviceID, d.DeviceName, d.Platform, d.TokenHash, d.PairedAt, time.Now().UnixMilli(),
	)
	if err != nil {
		return fmt.Errorf("写入设备记录失败: %w", err)
	}
	return nil
}

// GetDevice 按设备 ID 查询，同时校验 Token 哈希是否匹配。
// tokenHash 为空时跳过校验（仅用于内部查询）。
func (s *Store) GetDevice(deviceID, tokenHash string) (*model.Device, error) {
	row := s.db.QueryRow(`
		SELECT device_id, device_name, platform, token_hash, paired_at, last_seen_at, revoked
		FROM devices WHERE device_id = ?`, deviceID)

	var d model.Device
	var revoked int
	err := row.Scan(&d.DeviceID, &d.DeviceName, &d.Platform, &d.TokenHash,
		&d.PairedAt, &d.LastSeenAt, &revoked)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("查询设备失败: %w", err)
	}
	d.Revoked = revoked == 1

	if d.Revoked {
		return nil, ErrNotFound
	}
	if tokenHash != "" && d.TokenHash != tokenHash {
		return nil, ErrNotFound
	}
	return &d, nil
}

// TouchDevice 更新设备最后活跃时间
func (s *Store) TouchDevice(deviceID string) {
	_, _ = s.db.Exec(`UPDATE devices SET last_seen_at = ? WHERE device_id = ?`,
		time.Now().UnixMilli(), deviceID)
}

// ListDevices 返回全部已配对设备
func (s *Store) ListDevices() ([]*model.Device, error) {
	rows, err := s.db.Query(`
		SELECT device_id, device_name, platform, paired_at, last_seen_at, revoked
		FROM devices ORDER BY last_seen_at DESC`)
	if err != nil {
		return nil, fmt.Errorf("查询设备列表失败: %w", err)
	}
	defer rows.Close()

	var out []*model.Device
	for rows.Next() {
		var d model.Device
		var revoked int
		if err := rows.Scan(&d.DeviceID, &d.DeviceName, &d.Platform,
			&d.PairedAt, &d.LastSeenAt, &revoked); err != nil {
			return nil, err
		}
		d.Revoked = revoked == 1
		out = append(out, &d)
	}
	return out, rows.Err()
}

// RevokeDevice 吊销设备授权
func (s *Store) RevokeDevice(deviceID string) error {
	_, err := s.db.Exec(`UPDATE devices SET revoked = 1 WHERE device_id = ?`, deviceID)
	return err
}

// ---------- 内容 ----------

// IsDuplicate 判断该内容是否在保留窗口内已同步过。
// 这是防回环的关键：A 收到 B 的内容后写回剪贴板，若再次上报，
// 会因为 hash 命中而被拦截。
func (s *Store) IsDuplicate(hash string) (bool, error) {
	cutoff := time.Now().Add(-s.retention).UnixMilli()
	var n int
	err := s.db.QueryRow(
		`SELECT COUNT(1) FROM clips WHERE hash = ? AND created_at > ?`, hash, cutoff,
	).Scan(&n)
	if err != nil {
		return false, fmt.Errorf("去重查询失败: %w", err)
	}
	return n > 0, nil
}

// SaveClip 落库一条内容记录。msg_id 冲突时返回 ErrDuplicate（幂等）。
func (s *Store) SaveClip(c *model.Clip) (int64, error) {
	expiresAt := int64(0)
	if s.retention > 0 {
		expiresAt = time.Now().Add(s.retention).UnixMilli()
	}

	res, err := s.db.Exec(`
		INSERT INTO clips (msg_id, hash, kind, origin, src_device, src_name,
		                   text_content, file_id, mime, size, width, height, created_at, expires_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		c.MsgID, c.Hash, c.Kind, c.Origin, c.SrcDevice, c.SrcName,
		nullIfEmpty(c.Text), nullIfEmpty(c.FileID), nullIfEmpty(c.Mime),
		c.Size, c.Width, c.Height, c.CreatedAt, expiresAt,
	)
	if err != nil {
		if strings.Contains(err.Error(), "UNIQUE constraint failed") {
			return 0, ErrDuplicate
		}
		return 0, fmt.Errorf("保存内容失败: %w", err)
	}
	return res.LastInsertId()
}

// ListClips 返回最近的历史记录
func (s *Store) ListClips(limit int) ([]*model.Clip, error) {
	if limit <= 0 || limit > 500 {
		limit = 50
	}
	rows, err := s.db.Query(`
		SELECT id, msg_id, hash, kind, origin, src_device, src_name,
		       COALESCE(text_content,''), COALESCE(file_id,''), COALESCE(mime,''),
		       size, width, height, created_at, expires_at
		FROM clips ORDER BY created_at DESC LIMIT ?`, limit)
	if err != nil {
		return nil, fmt.Errorf("查询历史记录失败: %w", err)
	}
	defer rows.Close()

	var out []*model.Clip
	for rows.Next() {
		var c model.Clip
		if err := rows.Scan(&c.ID, &c.MsgID, &c.Hash, &c.Kind, &c.Origin,
			&c.SrcDevice, &c.SrcName, &c.Text, &c.FileID, &c.Mime,
			&c.Size, &c.Width, &c.Height, &c.CreatedAt, &c.ExpiresAt); err != nil {
			return nil, err
		}
		out = append(out, &c)
	}
	return out, rows.Err()
}

// CleanupExpired 清理过期内容及其关联文件与队列项
func (s *Store) CleanupExpired() (int, error) {
	now := time.Now().UnixMilli()

	// 先取出待删除的图片 fileId，用于删除磁盘文件
	rows, err := s.db.Query(`SELECT COALESCE(file_id,'') FROM clips WHERE expires_at > 0 AND expires_at < ?`, now)
	if err != nil {
		return 0, err
	}
	var fileIDs []string
	for rows.Next() {
		var fid string
		if err := rows.Scan(&fid); err == nil && fid != "" {
			fileIDs = append(fileIDs, fid)
		}
	}
	rows.Close()

	res, err := s.db.Exec(`DELETE FROM clips WHERE expires_at > 0 AND expires_at < ?`, now)
	if err != nil {
		return 0, fmt.Errorf("清理过期内容失败: %w", err)
	}
	n, _ := res.RowsAffected()

	// 清理过期的离线队列项
	_, _ = s.db.Exec(`DELETE FROM pending_deliveries WHERE expires_at < ?`, now)

	for _, fid := range fileIDs {
		_ = os.Remove(filepath.Join(s.fileDir, fid))
	}
	return int(n), nil
}

// ---------- 离线队列 ----------

// Enqueue 在目标设备离线时暂存一条待投递消息
func (s *Store) Enqueue(targetDevice string, clipID int64, ttl time.Duration) error {
	now := time.Now()
	_, err := s.db.Exec(`
		INSERT INTO pending_deliveries (target_dev, clip_id, enqueued_at, expires_at)
		VALUES (?, ?, ?, ?)`,
		targetDevice, clipID, now.UnixMilli(), now.Add(ttl).UnixMilli())
	if err != nil {
		return fmt.Errorf("写入离线队列失败: %w", err)
	}
	return nil
}

// DrainPending 取出并清除某设备的全部待投递记录
func (s *Store) DrainPending(targetDevice string) ([]*model.Clip, error) {
	rows, err := s.db.Query(`
		SELECT c.id, c.msg_id, c.hash, c.kind, c.origin, c.src_device, c.src_name,
		       COALESCE(c.text_content,''), COALESCE(c.file_id,''), COALESCE(c.mime,''),
		       c.size, c.width, c.height, c.created_at, c.expires_at
		FROM pending_deliveries p
		JOIN clips c ON c.id = p.clip_id
		WHERE p.target_dev = ? AND p.expires_at > ?
		ORDER BY p.enqueued_at ASC`, targetDevice, time.Now().UnixMilli())
	if err != nil {
		return nil, fmt.Errorf("读取离线队列失败: %w", err)
	}
	defer rows.Close()

	var out []*model.Clip
	for rows.Next() {
		var c model.Clip
		if err := rows.Scan(&c.ID, &c.MsgID, &c.Hash, &c.Kind, &c.Origin,
			&c.SrcDevice, &c.SrcName, &c.Text, &c.FileID, &c.Mime,
			&c.Size, &c.Width, &c.Height, &c.CreatedAt, &c.ExpiresAt); err != nil {
			return nil, err
		}
		out = append(out, &c)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	_, _ = s.db.Exec(`DELETE FROM pending_deliveries WHERE target_dev = ?`, targetDevice)
	return out, nil
}

// ---------- 文件 ----------

// SaveFile 把图片字节写入磁盘，返回生成的 fileId
func (s *Store) SaveFile(fileID, ext string, data []byte) error {
	return os.WriteFile(filepath.Join(s.fileDir, fileID+ext), data, 0o644)
}

// FilePath 返回某文件的绝对路径
func (s *Store) FilePath(fileID, ext string) string {
	return filepath.Join(s.fileDir, fileID+ext)
}

// FindFileExt 按 fileId 在磁盘上查找真实扩展名，找不到时返回空串。
// clips 表里的 file_id 不含扩展名，而下载 URL 必须带扩展名，
// 历史记录与离线补推在 mime 缺失时靠它兜底。
func (s *Store) FindFileExt(fileID string) string {
	if fileID == "" {
		return ""
	}
	matches, err := filepath.Glob(filepath.Join(s.fileDir, fileID+".*"))
	if err != nil || len(matches) == 0 {
		return ""
	}
	return filepath.Ext(matches[0])
}

// DiskUsage 统计文件目录占用字节数
func (s *Store) DiskUsage() (int64, error) {
	var total int64
	err := filepath.Walk(s.fileDir, func(_ string, info os.FileInfo, err error) error {
		if err != nil {
			return nil // 忽略单个文件的访问错误
		}
		if !info.IsDir() {
			total += info.Size()
		}
		return nil
	})
	return total, err
}

// ---------- 工具 ----------

func nullIfEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}

// fallbackSchema 是 migrations/001_init.sql 的精简副本，
// 用于工作目录不含 migrations 目录的场景。
const fallbackSchema = `
CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY, device_name TEXT NOT NULL, platform TEXT NOT NULL,
    token_hash TEXT NOT NULL, paired_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL DEFAULT 0, revoked INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS clips (
    id INTEGER PRIMARY KEY AUTOINCREMENT, msg_id TEXT NOT NULL UNIQUE,
    hash TEXT NOT NULL, kind TEXT NOT NULL, origin TEXT NOT NULL DEFAULT '',
    src_device TEXT NOT NULL, src_name TEXT NOT NULL DEFAULT '',
    text_content TEXT, file_id TEXT, mime TEXT,
    size INTEGER NOT NULL DEFAULT 0, width INTEGER NOT NULL DEFAULT 0,
    height INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS idx_clips_hash ON clips(hash);
CREATE INDEX IF NOT EXISTS idx_clips_created ON clips(created_at DESC);
CREATE TABLE IF NOT EXISTS pending_deliveries (
    id INTEGER PRIMARY KEY AUTOINCREMENT, target_dev TEXT NOT NULL,
    clip_id INTEGER NOT NULL, enqueued_at INTEGER NOT NULL, expires_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS idx_pending_target ON pending_deliveries(target_dev, enqueued_at);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
`
