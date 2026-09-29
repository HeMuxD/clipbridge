# ClipBridge 服务端镜像
#
# 多阶段构建：构建阶段带完整 Go 工具链，运行阶段只留一个静态二进制。
# 因为使用 modernc.org/sqlite（纯 Go 实现），所以可以做 CGO_ENABLED=0 的
# 完全静态编译，运行阶段用 alpine 即可，不需要任何 C 运行库。
#
# 单独构建（不使用 compose）：
#   docker build -f deploy/docker/Dockerfile -t clipbridge/server:1.0.0 server
#
# 注意：仓库里必须存在 go.sum。修改依赖后请在 server/ 下执行 `go mod tidy`
# 并把生成的 go.sum 一并提交，否则下面的 COPY go.mod go.sum 会直接失败。

# 基础镜像源：默认走 DaoCloud 公共镜像站（国内可直连），规避 Docker Hub
# 及飞牛 NAS 默认加速器 docker.fnnas.com 拉取 401 的问题。
# 海外环境或自有可用加速器时，可构建时覆盖回官方源：
#   docker build --build-arg GOLANG_IMAGE=golang:1.22-alpine --build-arg ALPINE_IMAGE=alpine:3.20 ...
ARG GOLANG_IMAGE=docker.m.daocloud.io/library/golang:1.22-alpine
ARG ALPINE_IMAGE=docker.m.daocloud.io/library/alpine:3.20

# ---------- 构建阶段 ----------
FROM ${GOLANG_IMAGE} AS builder

# 国内构建环境走镜像代理，避免拉不到依赖
ARG GOPROXY=https://goproxy.cn,direct
ENV GOPROXY=${GOPROXY} \
    CGO_ENABLED=0

WORKDIR /build

# 先只拷 go.mod/go.sum，让依赖层可以被 Docker 缓存复用。
# 这样改业务代码时不会重新拉一遍依赖。
COPY go.mod go.sum ./
RUN go mod download

COPY . .

# -trimpath + -s -w：去掉绝对路径与调试符号，得到体积小、可移植的静态二进制
ARG VERSION=1.0.0
RUN go build \
        -trimpath \
        -ldflags="-s -w -X main.version=${VERSION}" \
        -o /build/clipbridge \
        ./cmd/clipbridge

# ---------- 运行阶段 ----------
FROM ${ALPINE_IMAGE}

# ca-certificates：出站 HTTPS 需要；tzdata：日志时间戳正确
# wget：健康检查用
RUN apk add --no-cache ca-certificates tzdata wget && \
    addgroup -S clipbridge && \
    adduser -S -G clipbridge -h /var/lib/clipbridge clipbridge

WORKDIR /opt/clipbridge

COPY --from=builder /build/clipbridge .
COPY --from=builder /build/migrations ./migrations
# 兜底配置：仅在未挂载 config.yaml 时生效
COPY --from=builder /build/config.example.yaml ./config.yaml

# 数据目录：数据库与图片都落在这里，通过 volume 持久化。
# 这里先建好并 chown，命名卷首次创建时会继承该属主，
# 否则容器里的非 root 用户写不进去。
RUN mkdir -p /var/lib/clipbridge/files && \
    chown -R clipbridge:clipbridge /var/lib/clipbridge /opt/clipbridge

USER clipbridge

ENV CLIPBRIDGE_LISTEN=0.0.0.0:8080 \
    CLIPBRIDGE_DB_PATH=/var/lib/clipbridge/clipbridge.db \
    CLIPBRIDGE_FILE_DIR=/var/lib/clipbridge/files

EXPOSE 8080

# 数据目录必须挂出来，否则容器重建会丢历史记录
VOLUME ["/var/lib/clipbridge"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["/opt/clipbridge/clipbridge"]
CMD ["-config", "/opt/clipbridge/config.yaml"]
