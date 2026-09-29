#!/bin/sh
# 在部署机（NAS / 任意装了 Docker 的 Linux）上执行：首次部署或更新 ClipBridge 服务端。
#
# 用法：
#   cd <仓库>/deploy/docker
#   sh deploy.sh
#
# 设计成幂等：重复执行只会重建容器，不会动数据卷里的历史与图片。

set -eu

log() { printf '\n==> %s\n' "$*"; }
warn() { printf '\n[注意] %s\n' "$*"; }
die() { printf '\n[错误] %s\n' "$*" >&2; exit 1; }

[ -f docker-compose.yml ] || die "请在 deploy/docker 目录下执行本脚本"
[ -f config.yaml ] || die "缺少 config.yaml —— 仓库里应自带这一份，别删它"
[ -f nginx.https.conf.template ] || die "缺少 nginx 配置模板，请确认是在完整仓库里执行"

command -v docker >/dev/null 2>&1 || die "没有找到 docker 命令"

if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  die "docker compose 不可用（需要 Docker Compose v2，或独立的 docker-compose）"
fi

# ---------- 1. .env ----------
if [ ! -f .env ]; then
  cp .env.example .env
  log "已从 .env.example 生成 .env —— 请先编辑它，然后重新运行本脚本"
  echo "  vim .env"
  echo
  echo "  必填三项："
  echo "    CLIPBRIDGE_SERVER_NAME      你的域名"
  echo "    CLIPBRIDGE_PUBLIC_BASE_URL  https://<域名>:8443   ← 必须带端口"
  echo "    CLIPBRIDGE_JWT_SECRET       openssl rand -hex 32"
  exit 1
fi

# shellcheck disable=SC1091
set -a; . ./.env; set +a

[ -n "${CLIPBRIDGE_JWT_SECRET:-}" ] || die \
  ".env 里的 CLIPBRIDGE_JWT_SECRET 还是空的。生成一个：openssl rand -hex 32"
[ -n "${CLIPBRIDGE_PUBLIC_BASE_URL:-}" ] || die \
  ".env 里的 CLIPBRIDGE_PUBLIC_BASE_URL 还没填（要带端口，例如 https://nas.example.com:8443）"

case "${CLIPBRIDGE_PUBLIC_BASE_URL}" in
  https://*:*) : ;;
  https://*)   warn "CLIPBRIDGE_PUBLIC_BASE_URL 没带端口号。若对外不是 443，图片下载地址会拼错。" ;;
  *)           die "CLIPBRIDGE_PUBLIC_BASE_URL 必须是 https:// 开头（Android 9+ 拒绝明文）" ;;
esac

# ---------- 2. 证书 ----------
CERT_DIR="${CLIPBRIDGE_CERT_DIR:-./certs}"
if [ -f "$CERT_DIR/fullchain.pem" ] && [ -f "$CERT_DIR/privkey.pem" ]; then
  log "证书就绪：$CERT_DIR"
else
  warn "$CERT_DIR 下没有 fullchain.pem / privkey.pem —— 容器会降级为只监听明文 HTTP"
  echo "  Android 9+ 拒绝明文连接，手机端在证书就位前连不上。装证书（acme.sh + DNS-01）："
  echo
  echo "    acme.sh --install-cert -d ${CLIPBRIDGE_SERVER_NAME:-<你的域名>} --ecc \\"
  echo "      --fullchain-file $(pwd)/certs/fullchain.pem \\"
  echo "      --key-file       $(pwd)/certs/privkey.pem \\"
  echo "      --reloadcmd      \"docker restart clipbridge\""
  echo
  echo "  装好后重新执行本脚本即可。"
  mkdir -p "$CERT_DIR"
fi

# ---------- 3. 清掉旧部署的残留 ----------
# 上一版是"两个容器"：clipbridge(Go) + clipbridge-nginx(Nginx)。
# 合并成单镜像后，旧的 nginx 容器不会随新的 compose 消失，
# 它会一直占着 8443，导致新容器映射端口失败。
for name in clipbridge clipbridge-nginx; do
  if docker ps -a --format '{{.Names}}' | grep -qx "$name"; then
    log "移除已有容器 $name（数据在命名卷里，不受影响）"
    docker rm -f "$name" >/dev/null
  fi
done

# ---------- 4. 构建并启动 ----------
log "构建镜像并启动（首次要拉 golang/nginx 基础镜像，会慢一些）"
$DC up -d --build

# ---------- 5. 等健康检查 ----------
log "等待健康检查通过…"
i=0
while [ "$i" -lt 60 ]; do
  state=$(docker inspect --format '{{.State.Health.Status}}' clipbridge 2>/dev/null || echo unknown)
  [ "$state" = "healthy" ] && break
  i=$((i + 1))
  sleep 2
done

log "容器状态"
docker ps --filter name=clipbridge --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'

log "最近日志（一次性配对码打印在这里，把它填到客户端）"
docker logs --tail 40 clipbridge 2>&1 || true

log "容器内自检"
if docker exec clipbridge wget -qO- http://127.0.0.1:18080/healthz 2>/dev/null; then
  printf '\n'
else
  warn "应用健康检查没通过，请对照上面的日志排查"
fi

log "完成"
echo "  客户端里填的服务端地址：${CLIPBRIDGE_PUBLIC_BASE_URL}"
echo "  外部自检： curl -k ${CLIPBRIDGE_PUBLIC_BASE_URL}/healthz"
