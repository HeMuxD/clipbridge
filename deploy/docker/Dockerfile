# ClipBridge 服务端镜像（Go 二进制 + Nginx 在同一个镜像里）
#
# 为什么合并成一个镜像：
#   · 部署只有一个容器，没有 Docker 网络、没有跨容器依赖，NAS 上更好管
#   · 反代目标是同一个容器里的回环地址，图片 /f/ 由 Go 进程自己提供，
#     数据卷不需要让任何外部进程读到
#   · 用户只需要挂两个目录：数据目录 与 证书目录
#
# 构建（在仓库【根目录】执行，上下文必须是仓库根）：
#   docker build -f deploy/docker/Dockerfile -t clipbridge:0.0.2 .
#
# 基础镜像源：默认走 DaoCloud 公共镜像站（国内可直连），规避 Docker Hub
# 及飞牛 NAS 默认加速器 docker.fnnas.com 拉取 401 的问题。
# 海外环境可覆盖回官方源：
#   docker build --build-arg GOLANG_IMAGE=golang:1.22-alpine \
#                --build-arg NGINX_IMAGE=nginx:1.27-alpine ...

ARG GOLANG_IMAGE=docker.m.daocloud.io/library/golang:1.22-alpine
ARG NGINX_IMAGE=docker.m.daocloud.io/library/nginx:1.27-alpine

# Alpine 软件包源。apk 默认走 dl-cdn.alpinelinux.org —— 这个地址在国内
# 实测会长时间挂住（本项目踩过：community 的 APKINDEX 拉了数分钟没有任何进展，
# 构建就此卡死；同一时刻宿主机 curl 该地址却是正常的，问题出在构建容器内的网络路径）。
# 换成国内镜像后 0.1 秒完成。海外环境覆盖回官方：
#   docker build --build-arg ALPINE_MIRROR=https://dl-cdn.alpinelinux.org/alpine ...
ARG ALPINE_MIRROR=https://mirrors.aliyun.com/alpine

# ---------- 阶段 1：编译 Go 服务端 ----------
FROM ${GOLANG_IMAGE} AS builder

# 国内构建环境走镜像代理，避免拉不到依赖
ARG GOPROXY=https://goproxy.cn,direct
ENV GOPROXY=${GOPROXY} \
    CGO_ENABLED=0

WORKDIR /build

# 先只拷 go.mod/go.sum，让依赖层可以被缓存复用；改业务代码时不会重新拉依赖
COPY server/go.mod server/go.sum ./
RUN go mod download

# 服务端源码（.dockerignore 已排掉 data/、config.yaml 等不该进镜像的东西）
COPY server/ .

# -trimpath + -s -w：去掉绝对路径与调试符号，得到体积小、可移植的静态二进制
# 版本号注入到 internal/buildinfo.Version（服务端 -version 与 /healthz 都读它）
ARG VERSION=0.0.2
RUN go build \
        -trimpath \
        -ldflags="-s -w -X github.com/clipbridge/server/internal/buildinfo.Version=${VERSION}" \
        -o /build/clipbridge \
        ./cmd/clipbridge

# ---------- 阶段 2：Nginx + Go 二进制 ----------
FROM ${NGINX_IMAGE}

# 在 FROM 之前声明的 ARG 属于"全局作用域"，在阶段内使用必须重新声明一次
# （不带默认值 = 继承全局默认值），否则这里会拿到空字符串。
ARG ALPINE_MIRROR

# 运行阶段的基础镜像。除了跑 nginx + Go 二进制，还额外带了 curl / openssl / bash ——
# 三者都是"容器内自动签发证书"（可选功能，见 entrypoint.sh 的 ACME 段）需要的：
#   curl/openssl  acme.sh 用它俩做 HTTPS 请求与密钥生成
#   bash          acme.sh 在 busybox ash 下偶有兼容问题，装 bash 一劳永逸
# 不用这个功能的话它们只是多占几 MB，不影响任何行为。
#
# 先把 apk 源换掉再装：基础镜像里的 /etc/apk/repositories 指向 dl-cdn，
# 不换的话这一步在国内可能永久挂住（见上面 ALPINE_MIRROR 的说明）。
RUN if [ -n "$ALPINE_MIRROR" ]; then \
        sed -i -e "s|https://dl-cdn.alpinelinux.org/alpine|${ALPINE_MIRROR}|g" \
               -e "s|http://dl-cdn.alpinelinux.org/alpine|${ALPINE_MIRROR}|g" \
               /etc/apk/repositories; \
        echo "apk 源已切换为 $ALPINE_MIRROR:"; cat /etc/apk/repositories; \
    fi \
 && apk add --no-cache ca-certificates tzdata wget curl openssl bash

WORKDIR /opt/clipbridge

# 服务端二进制、建表 SQL 与兜底配置
COPY --from=builder /build/clipbridge /opt/clipbridge/clipbridge
COPY --from=builder /build/migrations /opt/clipbridge/migrations
COPY --from=builder /build/config.example.yaml /opt/clipbridge/config.yaml

# nginx 配置模板（entrypoint 会渲染成 /etc/nginx/conf.d/*.conf）
COPY deploy/docker/nginx.http.conf.template /opt/clipbridge/
COPY deploy/docker/nginx.https.conf.template /opt/clipbridge/
COPY deploy/docker/entrypoint.sh /opt/clipbridge/entrypoint.sh
RUN chmod +x /opt/clipbridge/entrypoint.sh

# 内置服务端只监听容器内的回环地址，外部一律经 nginx 进入
ENV CLIPBRIDGE_LISTEN=127.0.0.1:18080 \
    CLIPBRIDGE_DB_PATH=/var/lib/clipbridge/clipbridge.db \
    CLIPBRIDGE_FILE_DIR=/var/lib/clipbridge/files

# 数据目录与证书目录
RUN mkdir -p /var/lib/clipbridge/files /certs

# 8443 = HTTPS（对外主端口，避开被封的 443）
# 8080 = 明文 HTTP（局域网调试用）
EXPOSE 8443 8080

# 数据目录必须挂出来，否则容器重建会丢历史与图片
VOLUME ["/var/lib/clipbridge"]

# 直接探 Go 进程：nginx 起来但后端挂了，也应该判为不健康
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1:18080/healthz || exit 1

ENTRYPOINT ["/opt/clipbridge/entrypoint.sh"]
