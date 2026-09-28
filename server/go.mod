module github.com/clipbridge/server

go 1.22

// 依赖说明：
//
// · gorilla/websocket —— WebSocket 实现，成熟稳定
//
// · modernc.org/sqlite —— 纯 Go 的 SQLite 驱动。
//   之所以不用更常见的 mattn/go-sqlite3，是因为后者基于 CGO：
//   交叉编译到 Linux 需要目标平台的 C 工具链，
//   也无法构建 CGO_ENABLED=0 的完全静态二进制。
//   本项目走 Docker / 跨平台交叉编译，因此选纯 Go 方案。
//   代价是 CPU 密集型查询比 C 版本慢一些，但本项目是 I/O 密集型，
//   数据库操作只有简单的 INSERT/SELECT，影响可忽略。
//
// · gopkg.in/yaml.v3 —— 配置文件解析
require (
	github.com/gorilla/websocket v1.5.3
	gopkg.in/yaml.v3 v3.0.1
	modernc.org/sqlite v1.34.5
)

require (
	github.com/dustin/go-humanize v1.0.1 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/mattn/go-isatty v0.0.20 // indirect
	github.com/ncruces/go-strftime v0.1.9 // indirect
	github.com/remyoudompheng/bigfft v0.0.0-20230129092748-24d4a6f8daec // indirect
	golang.org/x/sys v0.22.0 // indirect
	modernc.org/libc v1.55.3 // indirect
	modernc.org/mathutil v1.6.0 // indirect
	modernc.org/memory v1.8.0 // indirect
)
