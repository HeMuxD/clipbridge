// Package buildinfo 存放构建期信息。
//
// 版本号在这里是【唯一来源】—— 服务端本体的 `-version` 输出、/healthz 的返回、
// 启动日志都读同一个值，不会出现"改了一处漏了另一处"。
//
// 发布构建时用 ldflags 覆盖：
//
//	go build -ldflags "-X github.com/clipbridge/server/internal/buildinfo.Version=1.2.3" ./cmd/clipbridge
//
// scripts/build.sh 与 deploy/docker/Dockerfile 已经这么做。
package buildinfo

// Version 是服务端版本号。
//
// 用 var 而不是 const，因为 -ldflags -X 只能改写包级变量。
var Version = "0.0.2"
