# ClipBridge 跨端同步协议 v1

本文件是**跨端契约的唯一基准**。服务端、Windows 客户端、Android 客户端必须严格对齐本文件。

## 1. 传输层

| 用途 | 协议 | 说明 |
| --- | --- | --- |
| 实时信令 | `WSS` (`wss://host/ws`) | 全双工长连接，只传控制消息与元数据 |
| 文件上传 | `HTTPS POST /api/upload` | `multipart/form-data`，图片二进制走这里 |
| 文件下载 | `HTTPS GET /f/{fileId}` | 由 Nginx 直接服务静态文件，可加签名 |
| 鉴权 | `HTTPS POST /api/pair` | 配对码换取长期 Token |

**禁止**让图片二进制走 WebSocket —— 单帧过大会导致分片与队头阻塞。

## 2. 握手与鉴权

### 2.1 配对流程（首次）

1. 服务端启动时生成一次性**配对码**（6 位数字，默认 10 分钟有效），打印在日志中；
2. 客户端首次运行时输入配对码，调用 `POST /api/pair`：

```json
{ "pairCode": "482913", "deviceId": "win-desktop-01", "deviceName": "我的台式机", "platform": "windows" }
```

3. 服务端校验通过后返回：

```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "deviceId": "win-desktop-01",
  "expiresAt": 1797536000000
}
```

4. 客户端持久化 Token：
   - Windows：DPAPI（`ProtectedData.Protect`）
   - Android：`EncryptedSharedPreferences` / Keystore

### 2.2 连接建立

```
GET wss://host/ws
Header: Authorization: Bearer <token>
```

服务端校验失败时以 `4401` 关闭码断开。

## 3. 消息信封

所有 WebSocket 消息为 UTF-8 JSON 对象，顶层必须包含 `type` 字段。

```json
{
  "type": "clip",
  "msgId": "550e8400-e29b-41d4-a716-446655440000",
  "ts": 1766000000000,
  "payload": { }
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | string | ✅ | 见下表 |
| `msgId` | string (UUIDv4) | ✅ | 消息唯一 ID，用于幂等与 ACK |
| `ts` | int64 | ✅ | 发送方毫秒时间戳 |
| `payload` | object | ✅ | 消息体，结构随 `type` 变化 |

### 3.1 消息类型

| type | 方向 | 说明 |
| --- | --- | --- |
| `hello` | C → S | 连接建立后的自我介绍 |
| `clip` | C ↔ S | 剪贴板 / 截图内容（核心消息） |
| `ack` | 双方 | 确认收到某条 `msgId` |
| `ping` / `pong` | 双方 | 心跳，30 秒间隔 |
| `device_list` | S → C | 在线设备列表变更 |
| `error` | S → C | 错误通知 |

## 4. `clip` 消息

### 4.1 文本

```json
{
  "type": "clip",
  "msgId": "8f1c...",
  "ts": 1766000000000,
  "payload": {
    "kind": "text",
    "hash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    "text": "要同步的内容",
    "origin": "clipboard",
    "srcDevice": "win-desktop-01",
    "srcName": "我的台式机"
  }
}
```

### 4.2 图片

```json
{
  "type": "clip",
  "msgId": "a72b...",
  "ts": 1766000000000,
  "payload": {
    "kind": "image",
    "hash": "3b1f...",
    "fileId": "ab12cd34ef56",
    "url": "https://host/f/ab12cd34ef56.png",
    "mime": "image/png",
    "size": 238941,
    "width": 1920,
    "height": 1080,
    "origin": "screenshot",
    "srcDevice": "pixel8",
    "srcName": "Pixel 8"
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `kind` | `"text"` \| `"image"` | 内容类型 |
| `hash` | string | 内容的 SHA-256 十六进制小写，用于去重与回环防护 |
| `text` | string | `kind=text` 时必填 |
| `fileId` | string | `kind=image` 时必填，服务端分配 |
| `url` | string | 图片下载地址，客户端用此拉取 |
| `mime` | string | 图片 MIME，统一 `image/png` 或 `image/jpeg` |
| `size` | int | 字节数 |
| `width` / `height` | int | 像素尺寸 |
| `origin` | enum | 内容来源，见下 |
| `srcDevice` | string | 来源设备 ID，服务端回填 |
| `srcName` | string | 来源设备名，服务端回填 |

**`origin` 取值**：`clipboard`（剪贴板） / `screenshot`（截图） / `share`（系统分享） / `manual`（应用内手动同步）

## 5. HTTP 接口一览

除 WebSocket 外，以下 HTTP 接口供客户端管理用。全部需要 `Authorization: Bearer <token>`（`/api/pair` 与 `/healthz` 除外）。

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/healthz` | 否 | 健康检查，返回在线连接数与版本 |
| `POST` | `/api/pair` | 否 | 用配对码换取 Token |
| `POST` | `/api/upload` | 是 | 上传图片，返回 `fileId` 与 `url` |
| `GET` | `/api/history` | 是 | 查询最近 100 条同步记录 |
| `GET` | `/api/devices` | 是 | 查询已配对设备与当前在线设备 |
| `GET` | `/f/{fileId}{ext}` | 否 | 下载图片（由 Nginx 直接服务） |

### `/api/history` 响应

```json
{
  "items": [
    {
      "msgId": "8f1c...",
      "kind": "text",
      "origin": "clipboard",
      "hash": "9f86...",
      "srcDevice": "win-desktop-01",
      "srcName": "我的台式机",
      "createdAt": 1766000000000,
      "text": "内容"
    },
    {
      "msgId": "a72b...",
      "kind": "image",
      "origin": "screenshot",
      "hash": "3b1f...",
      "srcDevice": "pixel8",
      "srcName": "Pixel 8",
      "createdAt": 1766000000000,
      "fileId": "ab12cd34ef56",
      "url": "https://host/f/ab12cd34ef56.png",
      "mime": "image/png",
      "size": 238941
    }
  ],
  "count": 2
}
```

### `/api/devices` 响应

```json
{
  "devices": [
    { "DeviceID": "win-desktop-01", "DeviceName": "我的台式机", "Platform": "windows", "PairedAt": 1766000000000, "LastSeenAt": 1766000000000, "Revoked": false }
  ],
  "online": [
    { "deviceId": "win-desktop-01", "deviceName": "我的台式机", "platform": "windows", "online": true }
  ]
}
```

> 注意：`devices` 数组复用 Go 结构体的字段名（首字母大写），
> `online` 数组走独立结构体（小驼峰）。这是当前实现的既成事实，
> 客户端解析时需按实际字段名处理。

## 6. 服务端处理流程

收到 `clip` 后：

1. **鉴权**：校验 Token 与设备白名单；
2. **限流**：检查单设备上传频率（默认 60 次/分钟）与文件大小上限（默认 20 MB）；
3. **回填**：将 `srcDevice` / `srcName` 写入 payload（客户端无需自己填）；
4. **去重**：查 `msgId` 是否处理过（幂等）；查 `hash` 是否在保留窗口内已广播过（防回环）；
5. **落库**：写入 `clips` 表；
6. **广播**：推给除来源设备外的所有在线设备；
7. **离线队列**：仅当 `limits.offline_replay_seconds > 0` 时才为离线设备入队，
   TTL 直接取该窗口（默认 **0 = 不入队**）；
8. **回 ACK**：向来源设备发送 `ack`。

> **"只同步当前这一次操作"是协议层的行为约定**，不是可选优化。
> 默认配置下设备离线就意味着错过这条内容，上线时不会再补推 ——
> 用户只关心自己此刻复制的东西，几分钟前的旧内容一次性刷进来只会是干扰。
> 需要容忍短暂网络抖动（例如重连空档）时，把窗口设成 60~120 秒。

设备上线时（`onOpen`）：

1. 取走该设备名下的待投递记录；
2. 若补推窗口为 0（默认）：**直接丢弃**，不下发，日志记 `已丢弃离线积压（未开启补推）`；
3. 否则按入队顺序补推，并广播设备列表变更。

## 7. 客户端处理流程

### 7.1 上行（检测到本地变化）

1. 读取内容 → 计算 SHA-256；
2. **查本地缓存**：`hash` 命中则跳过（这是防回环的第一道防线）；
3. 文本直接发；图片先 `POST /api/upload` 拿 `fileId` 与 `url`；
4. 发送 `clip` 消息，等待 ACK，超时 10 秒后重试（最多 3 次）。

**客户端不得在启动时扫描/读取既有内容。** 启动瞬间剪贴板里已有的内容、
截图目录里已存在的文件，都属于"以往的复制/截图"，必须登记为已处理，不得上报。

### 7.2 下行（收到远端内容）

1. **写入标记**：把该内容的 `hash` 登记进本地缓存 —— **必须在写剪贴板之前**；
2. 文本：两端都直接写入系统剪贴板。Android 是**静默写入**，不弹通知、不需要用户点任何按钮；
3. 图片：下载后 Android 存相册（`Pictures/ClipBridge`）并同时以 `content://` URI 写入剪贴板；
   Windows 写入剪贴板 `CF_BITMAP`；
4. **回环防护**：写入本地产生的变更事件，其 `hash` 命中缓存则丢弃。
   注意用"包含"判断而不是"命中即移除"—— Windows 对一次写入常常投递多次
   `WM_CLIPBOARDUPDATE`，先移除会导致后续那次被当成新内容重复上报；
5. 发送 `ack`。

> ⚠️ 第 1、4 步是必须实现的。缺少回环防护会导致 A→B→A→B 无限循环，服务端流量瞬间打爆。

### 7.3 Android 上行的特殊性

Android 10+ 起，后台应用读剪贴板会被系统拒绝（`getPrimaryClip()` 返回 `null`）。
无障碍服务**不在**系统豁免名单里，因此上行要按可靠性分层：

1. 先试读系统剪贴板，读到即用；
2. 读不到则回退：由无障碍事件（文本选中 + 点击"复制/剪切"）还原用户复制的内容；
3. 两路都拿不到时**不得伪造内容** —— 宁可漏同步；
4. 兜底入口：系统分享菜单（`ACTION_SEND`），零权限、100% 可靠。

**写**剪贴板不受该限制（`OP_WRITE_CLIPBOARD` 直接放行），所以下行可以做到完全静默。

## 8. 错误码

| code | 含义 | 客户端行为 |
| --- | --- | --- |
| `1001` | Token 无效或过期 | 清除本地 Token，重新进入配对流程 |
| `1002` | 设备未授权 | 提示用户重新配对 |
| `2001` | 请求过于频繁 | 指数退避重试 |
| `2002` | 文件过大 | 提示用户，压缩后重试 |
| `2003` | 内容重复（已去重） | 静默忽略，视为成功 |
| `5001` | 服务端内部错误 | 退避重试 |

## 9. 心跳与重连

- 客户端每 **30 秒** 发送 `ping`，服务端回 `pong`；
- 连续 **2 次** 未收到 `pong`（60 秒）判定连接失效，主动重连；
- 重连采用**指数退避**：1s、2s、4s、8s…… 上限 60s，加入 ±20% 随机抖动；
- Android 侧需注册 `ConnectivityManager.NetworkCallback`，网络恢复时立即重连。

## 10. 版本兼容

- WebSocket 路径带版本：`/ws/v1`
- 新增字段一律可选，客户端必须忽略未知字段
- 破坏性变更需提升路径版本号
