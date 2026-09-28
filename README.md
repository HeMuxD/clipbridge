# ClipBridge

跨端截图 / 剪贴板同步系统 —— Windows 与 Android 之间自动同步文本与截图，服务端自建。

> 完整技术评估见 `docs/可行性报告.md`

## 架构

```
Windows 客户端 ─┐
                ├─ WSS ─→ 服务端（Go） ─→ SQLite + 本地文件存储
Android 客户端 ─┘           ↑
                        Nginx（TLS 终止 / 反代）
```

中心化转发（Hub-and-Spoke）：所有客户端只与服务端建立一条 WebSocket 长连接，客户端之间互不直连。

- **控制信令** 走 WebSocket（`wss://`），只传元数据
- **图片二进制** 走 HTTP `POST /upload`，避免阻塞长连接
- **端到端加密**（可选）在业务层做 AES-256-GCM，服务端仅做密文盲转发

## 目录结构

```
clipbridge/
├── server/                    # 服务端（Go）
│   ├── cmd/clipbridge/        # 程序入口
│   ├── internal/
│   │   ├── config/            # 配置加载
│   │   ├── auth/              # 配对码 / Token 鉴权
│   │   ├── hub/               # WebSocket 连接池与广播
│   │   ├── store/             # SQLite + 文件存储
│   │   ├── api/               # HTTP 路由
│   │   └── model/             # 数据模型
│   └── migrations/            # 建表 SQL
├── clients/
│   ├── windows/               # Windows 客户端（C# / .NET 8）
│   │   └── src/
│   │       ├── Interop/       # Win32 P/Invoke（剪贴板、热键）
│   │       ├── Services/      # 剪贴板监听、截图目录监控、同步客户端
│   │       ├── Models/        # 消息模型
│   │       ├── UI/            # 托盘、设置窗口
│   │       └── Utils/         # 哈希、图片处理
│   └── android/               # Android 客户端（Kotlin）
│       └── app/src/main/
│           ├── java/com/clipbridge/app/
│           │   ├── service/   # 前台服务、无障碍服务
│           │   ├── data/      # Room、WebSocket
│           │   ├── ui/        # Compose 界面
│           │   ├── receiver/  # 分享接收、开机自启
│           │   └── util/
│           └── res/
├── shared/protocol/           # 跨端协议定义（协议基准）
├── deploy/                    # Nginx / systemd 部署配置
├── docs/                      # 文档
└── tools/                     # 辅助脚本
```

## 平台能力矩阵

| 能力      | Windows        | Android                     |
| ------- | -------------- | --------------------------- |
| 监听剪贴板文本 | ✅ 系统原生通知，无感    | ⚠️ 后台受限，需无障碍服务              |
| 捕获截图    | ✅ 剪贴板位图 + 目录监控 | ⚠️ MediaProjection，重启后需重新授权 |
| 写入剪贴板   | ✅ 直接写入         | ⚠️ 建议改为「相册 + 通知一键复制」        |
| 可靠性兜底   | —              | ✅ 系统分享菜单接收（零权限）             |

**重要前提**：Android 10+ 起，后台应用无法读取剪贴板，且**没有任何权限可以申请**。唯一免 Root 方案是无障碍服务。

## 开发路线

- **Phase 1（MVP，约 2 周）** 服务端文本广播 + Windows 文本双向 + Android 接收
- **Phase 2（第 3 周）** 图片通道、Android 无障碍服务、离线队列、历史记录
- **Phase 3（第 4~5 周）** MediaProjection、端到端加密、敏感内容过滤、保活引导

## 快速开始

见 `docs/开发指南.md`

## 安全提示

- 全链路必须 TLS（Android 9+ 默认禁止明文 HTTP）
- 敏感内容建议配置正则黑名单，避免密码 / 验证码被同步
- 服务端历史记录支持自动过期
