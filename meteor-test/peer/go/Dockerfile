ARG GO_IMAGE=golang:1.27.2-bookworm
FROM debian:bookworm-slim AS process-builder
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev && rm -rf /var/lib/apt/lists/*
COPY meteor-test/src/main/c/peer-process.c /tmp/peer_process.c
RUN gcc -std=c11 -O2 -Wall -Wextra -Werror -static /tmp/peer_process.c -o /peer-process
FROM ${GO_IMAGE} AS go
ENV GOTOOLCHAIN=local \
    GOWORK=off \
    CGO_ENABLED=0 \
    GOCACHE=/cache/go-build
RUN apt-get update && \
    apt-get install -y --no-install-recommends bash jq ca-certificates && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /opt/meteor-toolchain/go
COPY meteor-test/peer/go/go.mod meteor-test/peer/go/go.sum ./
# 依赖下载使用 HTTP/1，避免构建网络提前关闭 HTTP/2 下载；运行时保持默认协议栈。
RUN GODEBUG=http2client=0 go mod download
COPY meteor-test/peer/go/compatibility ./compatibility
RUN for module in compatibility/grpc-* compatibility/connect-* compatibility/hprose-* \
        compatibility/thrift/v* compatibility/dubbo/v1; do \
        (cd "$module" && GODEBUG=http2client=0 go mod download all); \
    done
WORKDIR /opt/meteor-toolchain/go-dubbo
COPY meteor-test/peer/go/dubbo/go.mod meteor-test/peer/go/dubbo/go.sum ./
RUN GODEBUG=http2client=0 go mod download
WORKDIR /opt/meteor-toolchain/go-oncrpc
COPY meteor-test/peer/go/oncrpc/go.mod meteor-test/peer/go/oncrpc/go.sum ./
RUN GODEBUG=http2client=0 go mod download
COPY --from=process-builder /peer-process /opt/meteor-test/peer-process
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["bash", "/workspace/meteor/meteor-test/peer/go/build-runtime.sh"]
