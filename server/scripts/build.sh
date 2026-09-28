#!/usr/bin/env bash
# ClipBridge 服务端构建脚本
#
# 用法：
#   ./scripts/build.sh              # 构建当前平台
#   ./scripts/build.sh linux        # 交叉编译到 Linux amd64

set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="${VERSION:-1.0.0}"
OUT_DIR="dist"
TARGET="${1:-native}"

echo "==> ClipBridge 服务端构建 v${VERSION}"
mkdir -p "$OUT_DIR"

case "$TARGET" in
  linux)
    echo "==> 目标平台：Linux amd64"
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
      -trimpath \
      -ldflags="-s -w -X main.version=${VERSION}" \
      -o "${OUT_DIR}/clipbridge-linux-amd64" \
      ./cmd/clipbridge
    echo "==> 产物：${OUT_DIR}/clipbridge-linux-amd64"
    ;;

  windows)
    echo "==> 目标平台：Windows amd64"
    CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build \
      -trimpath \
      -ldflags="-s -w -X main.version=${VERSION}" \
      -o "${OUT_DIR}/clipbridge-windows-amd64.exe" \
      ./cmd/clipbridge
    echo "==> 产物：${OUT_DIR}/clipbridge-windows-amd64.exe"
    ;;

  *)
    echo "==> 目标平台：当前系统"
    # 使用纯 Go 的 SQLite 驱动（modernc.org/sqlite），
    # 因此可以安全地关闭 CGO，得到完全静态、可任意拷贝的二进制
    CGO_ENABLED=0 go build \
      -trimpath \
      -ldflags="-s -w -X main.version=${VERSION}" \
      -o "${OUT_DIR}/clipbridge" \
      ./cmd/clipbridge
    echo "==> 产物：${OUT_DIR}/clipbridge"
    ;;
esac

echo
echo "==> 构建完成"
echo
echo "部署提示："
echo "  1. 把二进制、config.yaml、migrations/ 一起上传到服务器"
echo "  2. 参考 deploy/systemd/clipbridge.service 配置开机自启"
echo "  3. 参考 deploy/nginx/clipbridge.conf 配置反向代理与 TLS"
