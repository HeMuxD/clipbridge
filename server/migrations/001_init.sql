-- ClipBridge 数据库结构 v1
-- 引擎：SQLite 3

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- 已配对的设备
CREATE TABLE IF NOT EXISTS devices (
    device_id     TEXT PRIMARY KEY,              -- 客户端自生成的稳定 ID
    device_name   TEXT NOT NULL,                 -- 展示名，如「我的台式机」
    platform      TEXT NOT NULL,                 -- windows | android | macos | linux | ios
    token_hash    TEXT NOT NULL,                 -- Token 的 SHA-256，不存明文
    paired_at     INTEGER NOT NULL,              -- 配对时间（Unix 毫秒）
    last_seen_at  INTEGER NOT NULL DEFAULT 0,    -- 最后活跃时间
    revoked       INTEGER NOT NULL DEFAULT 0     -- 1 = 已吊销
);

CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen_at);

-- 同步内容记录
CREATE TABLE IF NOT EXISTS clips (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    msg_id       TEXT NOT NULL UNIQUE,           -- 幂等键
    hash         TEXT NOT NULL,                  -- 内容 SHA-256，用于去重与回环防护
    kind         TEXT NOT NULL,                  -- text | image
    origin       TEXT NOT NULL DEFAULT '',       -- clipboard | screenshot | share | manual
    src_device   TEXT NOT NULL,                  -- 来源设备 ID
    src_name     TEXT NOT NULL DEFAULT '',       -- 来源设备名（冗余，便于展示）
    text_content TEXT,                           -- kind=text 时的内容
    file_id      TEXT,                           -- kind=image 时的文件标识
    mime         TEXT,
    size         INTEGER NOT NULL DEFAULT 0,
    width        INTEGER NOT NULL DEFAULT 0,
    height       INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL,               -- 服务端接收时间（Unix 毫秒）
    expires_at   INTEGER NOT NULL DEFAULT 0      -- 过期时间，0 = 永不过期
);

CREATE INDEX IF NOT EXISTS idx_clips_hash       ON clips(hash);
CREATE INDEX IF NOT EXISTS idx_clips_created    ON clips(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_clips_expires    ON clips(expires_at);

-- 离线消息队列：目标设备离线时暂存
CREATE TABLE IF NOT EXISTS pending_deliveries (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    target_dev  TEXT NOT NULL,                   -- 目标设备 ID
    clip_id     INTEGER NOT NULL,                -- 关联 clips.id
    enqueued_at INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL,                -- TTL 24 小时
    FOREIGN KEY (clip_id) REFERENCES clips(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_pending_target ON pending_deliveries(target_dev, enqueued_at);
CREATE INDEX IF NOT EXISTS idx_pending_expires ON pending_deliveries(expires_at);

-- 元数据（配对码、schema 版本等）
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
