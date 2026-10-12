#!/bin/sh
# ClipBridge 单镜像启动脚本：同时拉起 Go 服务端与 Nginx。
#
# 为什么不用 supervisord/s6：这里只有两个进程，用一个 shell 脚本 + 信号转发
# 就够了，不必为此多装一个进程管理器、把镜像撑大几十 MB。
#
# 进程布局（容器内）：
#   nginx       8443(HTTPS) / 8080(HTTP)   ← 对外，TLS 终止 + 反代
#   clipbridge  127.0.0.1:18080            ← 只监听回环，外部访问不到
#   acme_worker 仅在开启 ACME 模式时存在    ← 后台签发/续期证书，见文件末尾
#
# 证书有两种来源，二选一：
#   A) 容器外管理（默认）：宿主机用 acme.sh 签好，把 fullchain.pem / privkey.pem
#      放进挂载给 /certs 的目录。改动证书后让容器 reload 即可。
#   B) 容器内自动管理：设置 CLIPBRIDGE_ACME_DOMAIN 即启用。容器自己下载 acme.sh、
#      用 DNS-01 签发、装进数据卷，并每 12 小时检查一次续期 ——
#      全程不需要宿主机上有 acme.sh，也不需要 sudo。
#      代价：DNS API 凭据要能进容器（见 .env.example 的证书段落）。

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

if [ "$HTTPS_PORT" = "443" ] || [ "$HTTP_PORT" = "80" ]; then
  log "提示：80/443 常被运营商封锁；本部署默认用 8443，通常无需改动。"
fi

# ---------- 证书位置：两种模式 ----------
# ACME 模式下证书由容器自己写，所以放在【数据卷】里（可写、随容器重建保留）；
# 外管模式下读挂载进来的 /certs（宿主机只读挂载）。
ACME_MODE=0
if [ -n "${CLIPBRIDGE_ACME_DOMAIN:-}" ]; then ACME_MODE=1; fi

if [ "$ACME_MODE" = "1" ]; then
  CERT_FILE="${CLIPBRIDGE_CERT_FILE:-/var/lib/clipbridge/certs/fullchain.pem}"
  KEY_FILE="${CLIPBRIDGE_KEY_FILE:-/var/lib/clipbridge/certs/privkey.pem}"
else
  CERT_FILE="${CLIPBRIDGE_CERT_FILE:-/certs/fullchain.pem}"
  KEY_FILE="${CLIPBRIDGE_KEY_FILE:-/certs/privkey.pem}"
fi

# ---------- 配置文件 ----------
CONFIG=/opt/clipbridge/config.yaml
if [ ! -f "$CONFIG" ]; then
  die "找不到配置文件 $CONFIG。
      若你把宿主机的 ./config.yaml 挂到了这个路径，注意一个常见坑：
      宿主机上该文件不存在时，Docker 会创建一个同名【目录】，容器随后启动失败。
      本仓库的 deploy/docker/config.yaml 就是要挂的那一份，请确认它还在。"
fi

# ---------- ACME 模式的参数校验 ----------
ACME_HOME=""
ACME_LOG=""
ACME_DOMAIN=""
ACME_EMAIL=""
ACME_DNS=""
ACME_SERVER=""
ACMESH_BASE=""
ACME_RENEW_INTERVAL=43200   # 12 小时
ACME_RETRY_INTERVAL=600     # 尚无证书时的重试间隔：10 分钟
APPLIED_CERT=""

if [ "$ACME_MODE" = "1" ]; then
  ACME_HOME="${CLIPBRIDGE_ACME_HOME:-/var/lib/clipbridge/.acme}"
  ACME_LOG="$ACME_HOME/acme.log"

  # ⚠️ 必须把工作目录告诉 acme.sh，否则它会用默认的 $HOME/.acme.sh ——
  #    在容器里就是 /root/.acme.sh，属于【可写层】而不是数据卷。
  #    那样一来账号密钥、域名密钥、证书元数据全都在容器重建时蒸发，
  #    只剩 certs/ 里那份静态证书：能用到过期，但永远不会续期。
  #    本项目踩过这个坑（第一版就是忘了设，日志里能看到域名密钥落在
  #    /root/.acme.sh/…）。指定到数据卷后，acme.sh 的全部状态都持久化。
  export LE_WORKING_DIR="$ACME_HOME"

  # 强制 acme.sh 的所有 HTTP 请求走 IPv4。
  #
  # 为什么必须这么做（本项目实测）：
  #   · Let's Encrypt 的 API 在 Cloudflare 后面，同时有 A 与 AAAA 记录；
  #     容器里默认会先试 IPv6，而 Docker 默认 bridge 没有 IPv6 出口 ——
  #     不是立刻失败，而是连接挂到超时。
  #   · 更致命的是 acme.sh 对 POST 请求【不设响应超时】，一次丢包就永久卡死
  #     （实测 finalize 那一步挂了数分钟，进程一直不动）。
  #   · 实测：容器内强制 IPv4 连续 5 次全部成功；不强制时偶发超时。
  # 设为 0 可关闭（比如你的环境 IPv6 更稳）。
  if [ "${CLIPBRIDGE_ACME_IPV4_ONLY:-1}" = "1" ]; then
    export ACME_USE_IPV4_REQUESTS=1
  fi
  ACME_DOMAIN="$CLIPBRIDGE_ACME_DOMAIN"
  ACME_EMAIL="${CLIPBRIDGE_ACME_EMAIL:-}"
  ACME_DNS="${CLIPBRIDGE_ACME_DNS:-dns_cf}"

  # acme.sh 官方推荐保持最新；这里默认走 jsDelivr 镜像，
  # 因为 github 的 raw.githubusercontent.com 在部分网络下慢到无法完成下载
  # （本项目实测只有 1.6 KB/s，294 KB 的主脚本直接超时）。
  ACMESH_BASE="${CLIPBRIDGE_ACMESH_BASE:-https://cdn.jsdelivr.net/gh/acmesh-official/acme.sh@master}"

  if [ -n "${CLIPBRIDGE_ACME_SERVER:-}" ]; then
    ACME_SERVER="$CLIPBRIDGE_ACME_SERVER"
  elif [ "${CLIPBRIDGE_ACME_STAGING:-0}" = "1" ]; then
    # 调试时强烈建议先用 staging：生产环境同一个域名每周只能签发 5 次，
    # 连着试几次就会把自己锁住。
    ACME_SERVER=letsencrypt_test
  else
    ACME_SERVER=letsencrypt
  fi

  # DNS API 凭据。优先从文件读（不进 `docker inspect`），文件不存在时用容器环境变量。
  ACME_ENV_FILE="${CLIPBRIDGE_ACME_ENV_FILE:-/acme-credentials}"
  if [ -d "$ACME_ENV_FILE" ]; then
    die "CLIPBRIDGE_ACME_ENV_FILE 指向的是一个【目录】而不是文件：$ACME_ENV_FILE
      Docker 在宿主机上该文件不存在时会创建同名目录。请确认那个文件真的存在，或去掉这个挂载。"
  fi
  if [ -f "$ACME_ENV_FILE" ]; then
    log "从 $ACME_ENV_FILE 读取 DNS API 凭据"
    set -a
    . "$ACME_ENV_FILE"
    set +a
  fi

  # 至少校验一下默认服务商，免得用户填了域名却漏了令牌，然后对着
  # "acme.sh 一直失败" 的日志发蒙。其它服务商请自行确认钩子需要哪些变量。
  if [ "$ACME_DNS" = "dns_cf" ] && [ -z "${CF_Token:-}" ]; then
    die "使用 dns_cf（Cloudflare）需要 API Token。
      在 .env 里设置 CF_Token=... ，或写进 $ACME_ENV_FILE。
      Token 权限只要 Zone → DNS → Edit，Zone Resources 限定到你的域名即可。"
  fi

  [ -n "$ACME_EMAIL" ] || log "提示：未设置 CLIPBRIDGE_ACME_EMAIL，证书到期前的提醒邮件不会收到（不影响签发）。"

  mkdir -p "$ACME_HOME" "$(dirname "$CERT_FILE")" "$(dirname "$KEY_FILE")"
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

cert_ready() { [ -s "$CERT_FILE" ] && [ -s "$KEY_FILE" ]; }

# 幂等：证书在就渲染 HTTPS 段，不在就把它删掉
apply_cert_config() {
  if cert_ready; then
    render /opt/clipbridge/nginx.https.conf.template "$CONF_DIR/20-https.conf"
    return 0
  fi
  rm -f "$CONF_DIR/20-https.conf"
  return 1
}

if apply_cert_config; then
  log "已启用 HTTPS，监听 ${HTTPS_PORT}，证书：${CERT_FILE}"
else
  log "警告：未找到证书（缺 $CERT_FILE 或 $KEY_FILE），本次【只】监听明文 HTTP。"
  if [ "$ACME_MODE" = "1" ]; then
    log "      ACME 模式已启用，后台会立刻尝试签发；签好后会自动 reload nginx 切换到 HTTPS。"
  else
    log "      把 fullchain.pem / privkey.pem 放到挂载给 /certs 的宿主机目录后重启容器即可启用 HTTPS。"
  fi
  log "      注意：Android 9+ 默认拒绝明文 HTTP，配好证书之前手机端连不上。"
fi

# 记下 nginx 当前生效的证书状态，后台任务据此判断"要不要重新渲染 + reload"
cert_fingerprint() {
  if [ -s "$CERT_FILE" ]; then
    sha256sum "$CERT_FILE" 2>/dev/null | cut -d' ' -f1
  else
    echo none
  fi
}
APPLIED_CERT="$(cert_fingerprint)"

# ---------- 启动 ----------
APP_PID=""
NGINX_PID=""
ACME_PID=""

shutdown() {
  trap - TERM INT
  log "收到退出信号，正在优雅关闭…"
  [ -n "$ACME_PID" ] && kill -TERM "$ACME_PID" 2>/dev/null || true
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

# ---------- 后台任务：容器内签发与续期证书 ----------
# 放在 nginx 起来之后启动，理由有两个：
#   1. 首次签发要等 DNS 传播，可能 1~2 分钟，不能让它阻塞启动
#      （没有证书时先按明文跑起来，签好了再 reload 切到 HTTPS，用户不必干等）
#   2. reload 需要 nginx 已经在跑
#
# 整段包在 `set +e` 的子 shell 里：证书出任何问题都只能影响它自己，
# 绝不能把主进程（nginx / Go）拖下水。

fetch() { # $1=url $2=dest
  wget -q -T 30 -O "$2" "$1" 2>/dev/null && [ -s "$2" ]
}

ensure_acmesh() {
  if [ -s "$ACME_HOME/acme.sh" ]; then return 0; fi

  log "首次运行：下载 acme.sh 到数据卷（之后不再重复下载）"
  mkdir -p "$ACME_HOME/dnsapi"

  if ! fetch "$ACMESH_BASE/acme.sh" "$ACME_HOME/acme.sh"; then
    log "下载 acme.sh 失败。可用 CLIPBRIDGE_ACMESH_BASE 换源，例如："
    log "  https://raw.githubusercontent.com/acmesh-official/acme.sh/master"
    return 1
  fi
  # 被网络中间设备拦下时也会"下载成功"，但拿到的是一页 HTML。
  # 不校验的话，后面 acme.sh 会以一个完全看不懂的语法错误挂掉。
  if ! grep -q "acmesh-official" "$ACME_HOME/acme.sh"; then
    log "下载到的 acme.sh 内容异常（可能是被拦截后返回的 HTML），已丢弃"
    rm -f "$ACME_HOME/acme.sh"
    return 1
  fi

  if ! fetch "$ACMESH_BASE/dnsapi/$ACME_DNS.sh" "$ACME_HOME/dnsapi/$ACME_DNS.sh"; then
    log "下载 DNS 钩子 $ACME_DNS.sh 失败 —— acme.sh 会退化成\"手动添加 TXT 记录\"模式，无法自动续期"
    return 1
  fi

  chmod +x "$ACME_HOME/acme.sh" "$ACME_HOME/dnsapi/$ACME_DNS.sh"
  log "acme.sh 就位（$(wc -c < "$ACME_HOME/acme.sh") 字节），DNS 钩子：$ACME_DNS"
  return 0
}

run_acme() {
  # ⚠️ 千万不要给 --issue 加 --log：acme.sh 3.1.6 解析这个参数时会多 shift 一次，
  #    直接以 "shift: can't shift that many" 失败（本项目踩过，用逐个参数隔离才定位到）。
  #    需要日志就把输出重定向到文件。
  "$ACME_HOME/acme.sh" "$@" >>"$ACME_LOG" 2>&1
}

issue_cert() {
  log "开始签发证书：$ACME_DOMAIN（DNS-01 / $ACME_DNS，首次通常 1~2 分钟）"

  # 用 set -- 拼参数，而不是 ${ACME_EMAIL:+-m "$ACME_EMAIL"} ——
  # 后者里的引号在 POSIX sh 下不会被当成引号解析，dash/busybox 下行为不可靠。
  set -- --issue --server "$ACME_SERVER" --keylength ec-256 \
         --dns "$ACME_DNS" -d "$ACME_DOMAIN"
  if [ -n "$ACME_EMAIL" ]; then
    set -- "$@" -m "$ACME_EMAIL"
  fi

  if ! run_acme "$@"; then
    log "签发失败，详见 $ACME_LOG。常见原因："
    log "  · API Token 权限不足或域名不在该账号下"
    log "  · DNS 传播太慢（随后会自动重试）"
    log "  · 生产环境配额已用尽（调试请设 CLIPBRIDGE_ACME_STAGING=1）"
    return 1
  fi

  if ! run_acme --install-cert -d "$ACME_DOMAIN" --ecc \
                --key-file "$KEY_FILE" --fullchain-file "$CERT_FILE"; then
    log "证书安装到 $CERT_FILE 失败，详见 $ACME_LOG"
    return 1
  fi

  log "证书已签发并安装：$CERT_FILE"
  return 0
}

# 证书状态相对 nginx 当前配置有变化时，重新渲染并热加载。
# 用内容摘要而不是 mtime：acme.sh 每次 cron 都会重写文件，只看时间会无谓 reload。
sync_cert_to_nginx() {
  fresh="$(cert_fingerprint)"
  if [ "$fresh" = "$APPLIED_CERT" ]; then return 0; fi

  if apply_cert_config; then
    if nginx -s reload 2>/dev/null; then
      APPLIED_CERT="$fresh"
      log "证书已生效，nginx 已 reload（HTTPS 监听 ${HTTPS_PORT}，现有连接不中断）"
    elif [ -n "$NGINX_PID" ] && kill -HUP "$NGINX_PID" 2>/dev/null; then
      # 个别情况下 nginx -s reload 找不到 pid 文件（自定义过 nginx.conf），
      # 直接给 master 发 HUP 是等价的。
      APPLIED_CERT="$fresh"
      log "证书已生效，已通过 HUP 让 nginx 重载配置"
    else
      log "nginx 重载失败，证书文件本身已就位；重启容器即可生效"
    fi
  fi
}

acme_worker() {
  log "ACME 模式已启用：域名=$ACME_DOMAIN 服务商钩子=$ACME_DNS CA=$ACME_SERVER"

  if ! ensure_acmesh; then
    log "acme.sh 不可用，本次不做自动签发与续期；已有证书仍会继续使用"
    return 0
  fi

  if ! cert_ready; then
    issue_cert || true
    sync_cert_to_nginx
  fi

  while :; do
    if cert_ready; then
      # acme.sh 自己判断"是否临近到期"，没到期的 --cron 是空操作，
      # 所以这里固定 12 小时跑一次不会有任何副作用。
      sleep "$ACME_RENEW_INTERVAL"
      run_acme --cron --home "$ACME_HOME" || true
      sync_cert_to_nginx
    else
      sleep "$ACME_RETRY_INTERVAL"
      log "仍无可用证书，重试签发"
      issue_cert || true
      sync_cert_to_nginx
    fi
  done
}

if [ "$ACME_MODE" = "1" ]; then
  # set +e 是有意的：这个子 shell 里的任何失败都不应该影响主流程
  (
    set +e
    acme_worker
  ) &
  ACME_PID=$!
  log "证书后台任务 pid=$ACME_PID"
fi

# 任一主进程退出就整体退出，交给 Docker 的 restart 策略拉起
while kill -0 "$APP_PID" 2>/dev/null && kill -0 "$NGINX_PID" 2>/dev/null; do
  sleep 2
done

log "检测到有进程退出，容器即将结束"
if ! kill -0 "$APP_PID" 2>/dev/null; then
  log "退出的进程是 clipbridge —— 请查看上方日志里的报错"
else
  log "退出的进程是 nginx —— 通常是证书路径或配置有误"
  if [ "$ACME_MODE" = "1" ]; then
    log "  证书实际位置：$CERT_FILE（ACME 模式下由容器自己签发，不在 /certs）"
    log "  上一次签发/续期日志：$ACME_LOG"
  fi
fi

[ -n "$ACME_PID" ] && kill -TERM "$ACME_PID" 2>/dev/null || true
[ -n "$APP_PID" ] && kill -TERM "$APP_PID" 2>/dev/null || true
[ -n "$NGINX_PID" ] && kill -TERM "$NGINX_PID" 2>/dev/null || true
wait 2>/dev/null || true
exit 1
