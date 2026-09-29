#!/bin/sh
# ClipBridge 单镜像启动脚本：同时拉起 Go 服务端与 Nginx。
#
# 为什么不用 supervisord/s6：这里只有两个进程，用一个 shell 脚本 + 信号转发
# 就够了，不必为此多装一个进程管理器、把镜像撑大几十 MB。
#
# 进程布局（容器内）：
#   nginx       8443(HTTPS) / 8080(HTTP)   ← 对外，TLS 终止 + 反代
#   clipbridge  127.0.0.1:18080            ← 只监听回环，外部访问不到

set -eu

log() { echo "[entrypoint] $*"; }
die() { echo "[entrypoint][ERROR] $*" >&2; exit 1; }

# ---------- 必填项 ----------
[ -n "${CLIPBRIDGE_JWT_SECRET:-}" ] || die \
  "必须设置 CLIPBRIDGE_JWT_SECRET，否则容器每次重建所有设备都要重新配对。生成：openssl rand -hex 32"

[ -n "${CLIPBRIDGE_PUBLIC_BASE_URL:-}" ] || die \
  "必须设置 CLIPBRIDGE_PUBLIC_BASE_URL（用于拼接图片下载地址），例如 https://nas.example.com:8443"

# ---------- 可选项 ----------
SERVER_NAME="${CLIPBRIDGE_SERVER_NAME:-_}"
HTTPS_PORT="${CLIPBRIDGE_HTTPS_PORT:-8443}"
HTTP_PORT="${CLIPBRIDGE_HTTP_PORT:-8080}"
CERT_FILE="${CLIPBRIDGE_CERT_FILE:-/certs/fullchain.pem}"
KEY_FILE="${CLIPBRIDGE_KEY_FILE:-/certs/privkey.pem}"

if [ "$HTTPS_PORT" = "443" ] || [ "$HTTP_PORT" = "80" ]; then
  log "提示：80/443 常被运营商封锁；本部署默认用 8443，通常无需改动。"
fi

# ---------- 配置文件 ----------
CONFIG=/opt/clipbridge/config.yaml
if [ ! -f "$CONFIG" ]; then
  die "找不到配置文件 $CONFIG。
      若你把宿主机的 ./config.yaml 挂到了这个路径，注意一个常见坑：
      宿主机上该文件不存在时，Docker 会创建一个同名【目录】，容器随后启动失败。
      本仓库的 deploy/docker/config.yaml 就是要挂的那一份，请确认它还在。"
fi

# ---------- 渲染 nginx 配置 ----------
CONF_DIR=/etc/nginx/conf.d
rm -f "$CONF_DIR"/*.conf

render() {
  # $1 = 模板  $2 = 输出
  sed \
    -e "s|__SERVER_NAME__|${SERVER_NAME}|g" \
    -e "s|__HTTPS_PORT__|${HTTPS_PORT}|g" \
    -e "s|__HTTP_PORT__|${HTTP_PORT}|g" \
    -e "s|__CERT_FILE__|${CERT_FILE}|g" \
    -e "s|__KEY_FILE__|${KEY_FILE}|g" \
    "$1" > "$2"
}

render /opt/clipbridge/nginx.http.conf.template "$CONF_DIR/10-http.conf"
log "已启用明文 HTTP，监听 ${HTTP_PORT}（局域网调试用）"

if [ -f "$CERT_FILE" ] && [ -f "$KEY_FILE" ]; then
  render /opt/clipbridge/nginx.https.conf.template "$CONF_DIR/20-https.conf"
  log "已启用 HTTPS，监听 ${HTTPS_PORT}，证书：${CERT_FILE}"
else
  log "警告：未找到证书（缺 $CERT_FILE 或 $KEY_FILE），本次【只】监听明文 HTTP。"
  log "      把 fullchain.pem / privkey.pem 放到挂载给 /certs 的宿主机目录后重启容器即可启用 HTTPS。"
  log "      注意：Android 9+ 默认拒绝明文 HTTP，配好证书之前手机端连不上。"
fi

# ---------- 启动 ----------
APP_PID=""
NGINX_PID=""

shutdown() {
  trap - TERM INT
  log "收到退出信号，正在优雅关闭…"
  [ -n "$APP_PID" ] && kill -TERM "$APP_PID" 2>/dev/null || true
  [ -n "$NGINX_PID" ] && kill -TERM "$NGINX_PID" 2>/dev/null || true
  [ -n "$APP_PID" ] && wait "$APP_PID" 2>/dev/null || true
  [ -n "$NGINX_PID" ] && wait "$NGINX_PID" 2>/dev/null || true
  exit 0
}
trap shutdown TERM INT

# Go 服务端：配置由 -config 指定；数据库路径等由环境变量覆盖
/opt/clipbridge/clipbridge -config "$CONFIG" &
APP_PID=$!

# 给 Go 进程一点时间把监听端口建起来，nginx 反代即刻就能用
sleep 1

nginx -g 'daemon off;' &
NGINX_PID=$!

log "clipbridge pid=$APP_PID，nginx pid=$NGINX_PID"

# 任一进程退出就整体退出，交给 Docker 的 restart 策略拉起
while kill -0 "$APP_PID" 2>/dev/null && kill -0 "$NGINX_PID" 2>/dev/null; do
  sleep 2
done

log "检测到有进程退出，容器即将结束"
if ! kill -0 "$APP_PID" 2>/dev/null; then
  log "退出的进程是 clipbridge —— 请查看上方日志里的报错"
else
  log "退出的进程是 nginx —— 通常是证书路径或配置有误"
fi

[ -n "$APP_PID" ] && kill -TERM "$APP_PID" 2>/dev/null || true
[ -n "$NGINX_PID" ] && kill -TERM "$NGINX_PID" 2>/dev/null || true
wait 2>/dev/null || true
exit 1
