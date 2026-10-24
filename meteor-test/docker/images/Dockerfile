# syntax=docker/dockerfile:1.7
# Build from the repository root. Every target is a Linux amd64/arm64 image;
# compilers execute for the selected target platform, not the host platform.
ARG JAVA_BUILD_IMAGE=eclipse-temurin:17-jdk-noble
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-noble
ARG DOCKER_CLI_IMAGE=docker:29.5.3-cli
ARG PYTHON_IMAGE=python:3.13-slim-bookworm
ARG NODE_IMAGE=node:24-bookworm-slim
ARG GO_IMAGE=golang:1.27.2-bookworm
ARG DEBIAN_IMAGE=debian:bookworm-slim
ARG ENVOY_IMAGE=envoyproxy/envoy@sha256:08acc53db6f4ac46e77e36b203c5254b4a2549869314bb54cf97aab81baa4566

# Node SDKs have separate dependency trees and use their committed lock files.
FROM ${NODE_IMAGE} AS node-build
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
RUN apt-get update && \
    apt-get install -y --no-install-recommends bash ca-certificates curl && \
    rm -rf /var/lib/apt/lists/*
COPY meteor-test/src/test/node/hprose/ /opt/meteor-peer/node/hprose/
COPY meteor-test/src/test/node/connect/ /opt/meteor-peer/node/connect/
COPY meteor-test/src/test/node/jsonrpc/ /opt/meteor-peer/node/jsonrpc/
COPY meteor-test/src/test/node/grpcweb/ /opt/meteor-peer/node/grpcweb/
RUN set -eu; \
    for peer in hprose connect jsonrpc grpcweb; do \
        cd "/opt/meteor-peer/node/${peer}"; \
        npm ci --ignore-scripts --no-audit --no-fund; \
    done; \
    cd /opt/meteor-peer/node/grpcweb; \
    node build.js; \
    mkdir -p /opt/meteor-peer/assets/grpcweb; \
    cp browser.bundle.js /opt/meteor-peer/assets/grpcweb/browser.bundle.js

# Foreign Node processes execute here, outside the Java test runner container.
FROM node-build AS node
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["node", "--version"]

# Chromium must match the exact Playwright version from package-lock.json.
FROM node-build AS browser
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/meteor-peer/browsers
RUN cd /opt/meteor-peer/node/grpcweb && \
    node node_modules/playwright/cli.js install --with-deps chromium && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["node", "--version"]

# Build three independent Python environments, then generate SDK-owned contracts.
FROM ${PYTHON_IMAGE} AS python-build
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
RUN apt-get update && \
    apt-get install -y --no-install-recommends gcc g++ libc6-dev && \
    rm -rf /var/lib/apt/lists/*
COPY meteor-test/src/test/python/grpc/requirements.txt /sources/python/grpc/requirements.txt
COPY meteor-test/src/test/python/thrift/requirements.txt /sources/python/thrift/requirements.txt
COPY meteor-test/src/test/python/soap/requirements.txt /sources/python/soap/requirements.txt
RUN set -eu; \
    for peer in grpc thrift soap; do \
        python -m venv "/opt/meteor-peer/python-${peer}"; \
        "/opt/meteor-peer/python-${peer}/bin/python" -m pip install \
            --requirement "/sources/python/${peer}/requirements.txt"; \
        cp "/sources/python/${peer}/requirements.txt" \
            "/opt/meteor-peer/python-${peer}/requirements-installed.txt"; \
    done
COPY met-example/src/main/resources/proto/order.proto /sources/proto/order.proto
COPY meteor-test/src/test/python/soap/peer.py /sources/python/soap/peer.py
RUN mkdir -p /opt/meteor-peer/python-grpc-generated /opt/meteor-peer/python-soap-generated && \
    /opt/meteor-peer/python-grpc/bin/python -m grpc_tools.protoc \
       -I /sources/proto \
       --python_out=/opt/meteor-peer/python-grpc-generated \
       --grpc_python_out=/opt/meteor-peer/python-grpc-generated \
       /sources/proto/order.proto && \
    /opt/meteor-peer/python-soap/bin/python /sources/python/soap/peer.py \
       contract --output /opt/meteor-peer/python-soap-generated/orders.wsdl && \
    cp /sources/python/grpc/requirements.txt \
       /opt/meteor-peer/python-grpc-generated/requirements-installed.txt && \
    cp /sources/python/soap/requirements.txt \
       /opt/meteor-peer/python-soap-generated/requirements-installed.txt

# The final Python image contains SDK runtimes, but no compiler or build headers.
FROM ${PYTHON_IMAGE} AS python
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
RUN apt-get update \
    && apt-get install -y --no-install-recommends libstdc++6 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=python-build /opt/meteor-peer/ /opt/meteor-peer/
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["python", "--version"]

# libtirpc and rpcgen are confined to their own native build/runtime images.
FROM ${DEBIAN_IMAGE} AS native-build
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        gcc libc6-dev libtirpc-dev rpcsvc-proto python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY meteor-test/src/test/native/ /sources/meteor-test/src/test/native/
COPY meteor-test/src/test/rpc/interop.x /sources/meteor-test/src/test/rpc/interop.x
RUN python3 /sources/meteor-test/src/test/native/build-native.py \
    --output /opt/meteor-peer/native

FROM ${DEBIAN_IMAGE} AS native
RUN apt-get update \
    && apt-get install -y --no-install-recommends libtirpc3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=native-build /opt/meteor-peer/native/ /opt/meteor-peer/native/
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["/opt/meteor-peer/native/oncrpc_peer", "--help"]

# Go uses the committed module graph and never downloads an implicit toolchain.
FROM ${GO_IMAGE} AS go-build
ENV GOTOOLCHAIN=local \
    GOWORK=off \
    CGO_ENABLED=0
WORKDIR /sources/meteor-test/src/test/go
COPY meteor-test/src/test/go/go.mod meteor-test/src/test/go/go.sum ./
RUN go mod download
COPY meteor-test/src/test/go/ ./
RUN mkdir -p /opt/meteor-peer/go \
    && go build -mod=readonly -trimpath -buildvcs=false \
        -o /opt/meteor-peer/go/order-peer . \
    && go version > /opt/meteor-peer/go/go-version.txt \
    && cd /opt/meteor-peer/go \
    && sha256sum order-peer > order-peer.sha256

FROM ${DEBIAN_IMAGE} AS go
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=go-build /opt/meteor-peer/go/ /opt/meteor-peer/go/
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["/opt/meteor-peer/go/order-peer", "--help"]

# Java official SDKs use the independently resolved classpath mounted by the runner.
FROM ${JAVA_RUNTIME_IMAGE} AS java
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["java", "-version"]

# JDBC discovery has a separate database process rather than an in-runner H2 JVM.
FROM java AS h2
ARG H2_VERSION=2.3.232
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/meteor-peer/h2 \
    && curl --fail --show-error --silent --location \
        "https://repo.maven.apache.org/maven2/com/h2database/h2/${H2_VERSION}/h2-${H2_VERSION}.jar" \
        --output /opt/meteor-peer/h2/h2.jar \
    && sha256sum /opt/meteor-peer/h2/h2.jar > /opt/meteor-peer/h2/h2.jar.sha256
CMD ["java", "-cp", "/opt/meteor-peer/h2/h2.jar", "org.h2.tools.Server", "-tcp", "-tcpAllowOthers", "-ifNotExists", "-tcpPort", "9092"]

# Envoy remains in its own upstream image; no executable is extracted onto the host.
FROM ${ENVOY_IMAGE} AS envoy
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["/usr/local/bin/envoy", "--version"]

# The runner needs only Java and Docker coordination, plus standard fixture tools.
FROM ${DOCKER_CLI_IMAGE} AS docker-cli

FROM ${JAVA_BUILD_IMAGE} AS runner
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl git openssl python3 unzip \
    && rm -rf /var/lib/apt/lists/*
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=node-build /opt/meteor-peer/assets/ /opt/meteor-peer/assets/
COPY --from=python-build /opt/meteor-peer/python-grpc-generated/ /opt/meteor-peer/python-grpc-generated/
COPY --from=python-build /opt/meteor-peer/python-soap-generated/ /opt/meteor-peer/python-soap-generated/
COPY . /workspace/meteor/
ENV JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["java", "-version"]