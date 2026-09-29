#!/usr/bin/env bash
# ClipBridge 服务端构建脚本
#
# 用法：
#   ./scripts/build.sh              # 构建当前平台
#   ./scripts/build.sh linux        # 交叉编译到 Linux amd64
#   ./scripts/build.sh windows      # 交叉编译到 Windows amd64
#   ./scripts/build.sh all          # 一次产出 linux / windows 两个目标
#
# 版本号：
#   VERSION=0.0.1 ./scripts/build.sh linux
#   默认 0.0.1，与服务端 internal/buildinfo 的默认值保持一致。

set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="${VERSION:-0.0.1}"
OUT_DIR="dist"
TARGET="${1:-native}"

# 版本号的唯一来源是 internal/buildinfo.Version，这里用 ldflags 覆盖它。
# -s -w 去掉调试符号，-trimpath 去掉绝对路径，保证可复现、可移植。
VERSION_PKG="github.com/clipbridge/server/internal/buildinfo.Version"
build_one() { # $1=GOOS $2=GOARCH $3=输出文件名
  echo "==> ${1}/${2} → ${OUT_DIR}/$3"
  # 使用纯 Go 的 SQLite 驱动（modernc.org/sqlite），
  # 因此可以安全地关闭 CGO，得到完全静态、可任意拷贝的二进制
  CGO_ENABLED=0 GOOS="$1" GOARCH="$2" go build \
    -trimpath \
    -ldflags="-s -w -X ${VERSION_PKG}=${VERSION}" \
    -o "${OUT_DIR}/$3" \
    ./cmd/clipbridge
}

echo "==> ClipBridge 服务端构建 v${VERSION}"
mkdir -p "$OUT_DIR"

case "$TARGET" in
  linux)
    build_one linux amd64 clipbridge-linux-amd64
    ;;

  windows)
    build_one windows amd64 clipbridge-windows-amd64.exe
    ;;

  darwin)
    build_one darwin arm64 clipbridge-darwin-arm64
    ;;

  all)
    build_one linux amd64 clipbridge-linux-amd64
    build_one windows amd64 clipbridge-windows-amd64.exe
    build_one darwin arm64 clipbridge-darwin-arm64
    ;;

  *)
    echo "==> 目标平台：当前系统"
    CGO_ENABLED=0 go build \
      -trimpath \
      -ldflags="-s -w -X ${VERSION_PKG}=${VERSION}" \
      -o "${OUT_DIR}/clipbridge" \
      ./cmd/clipbridge
    ;;
esac

echo
echo "==> 构建完成，产物在 ${OUT_DIR}/"
ls -la "$OUT_DIR"
echo
echo "校验版本号："
for f in "$OUT_DIR"/clipbridge*; do
  [ -f "$f" ] || continue
  case "$f" in
    *.exe|*windows*) echo "  $f（Windows 产物，请在 Windows 上执行 -version 验证）" ;;
    *) "$f" -version 2>/dev/null | sed "s/^/  /" || echo "  $f（当前平台无法执行，跳过）" ;;
  esac
done
echo
echo "部署提示："
echo "  1. 把二进制、config.yaml、migrations/ 一起上传到服务器"
echo "  2. 参考 deploy/systemd/clipbridge.service 配置开机自启"
echo "  3. 参考 deploy/nginx/clipbridge.conf 配置反向代理与 TLS"
