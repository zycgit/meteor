# Build context is the Meteor repository root; mounted peer sources build at startup.
ARG NODE_IMAGE=node:24-bookworm-slim
FROM debian:bookworm-slim AS process-builder
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev && rm -rf /var/lib/apt/lists/*
COPY meteor-test/src/main/c/peer-process.c /tmp/peer_process.c
RUN gcc -std=c11 -O2 -Wall -Wextra -Werror -static /tmp/peer_process.c -o /peer-process
FROM ${NODE_IMAGE} AS dependencies
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    PLAYWRIGHT_BROWSERS_PATH=/opt/meteor-toolchain/browsers
RUN apt-get update && \
    apt-get install -y --no-install-recommends bash util-linux ca-certificates curl && \
    rm -rf /var/lib/apt/lists/*
COPY meteor-test/peer/node/hprose/package*.json /opt/meteor-toolchain/node/hprose/
COPY meteor-test/peer/node/connect/package*.json /opt/meteor-toolchain/node/connect/
COPY meteor-test/peer/node/jsonrpc/package*.json /opt/meteor-toolchain/node/jsonrpc/
COPY meteor-test/peer/node/grpcweb/package*.json /opt/meteor-toolchain/node/grpcweb/
COPY meteor-test/peer/node/thrift/package*.json /opt/meteor-toolchain/node/thrift/
COPY meteor-test/peer/node/soap/package*.json /opt/meteor-toolchain/node/soap/
COPY meteor-test/peer/node/xmlrpc/package*.json /opt/meteor-toolchain/node/xmlrpc/
COPY meteor-test/peer/node/dubbo/package*.json /opt/meteor-toolchain/node/dubbo/
COPY meteor-test/peer/node/oncrpc/package*.json /opt/meteor-toolchain/node/oncrpc/
COPY meteor-test/peer/node/triple-alpha/package*.json /opt/meteor-toolchain/node/triple-alpha/
COPY meteor-test/peer/node/hessian/package*.json /opt/meteor-toolchain/node/hessian/
RUN for peer in hprose connect jsonrpc grpcweb thrift soap xmlrpc dubbo oncrpc triple-alpha hessian; do \
        cd "/opt/meteor-toolchain/node/${peer}" && \
        npm ci --ignore-scripts --no-audit --no-fund || exit; \
    done
# 每条 SDK 版本线保留独立 node_modules；运行场景共用原来的 Node/browser 容器。
COPY meteor-test/peer/node/versions/manifests /opt/meteor-toolchain/node/sdk-versions
RUN for profile in /opt/meteor-toolchain/node/sdk-versions/*; do \
        cd "$profile" && npm ci --ignore-scripts --no-audit --no-fund || exit; \
    done
WORKDIR /workspace/meteor
ENTRYPOINT []

FROM dependencies AS node
ENV METEOR_PEER_RUNTIME=node
COPY --from=process-builder /peer-process /opt/meteor-test/peer-process
CMD ["node", "/workspace/meteor/meteor-test/peer/node/build-runtime.js"]

# Chromium uses the exact Playwright version from the committed lockfile.
FROM dependencies AS browser
ENV METEOR_PEER_RUNTIME=browser
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources && \
    cd /opt/meteor-toolchain/node/grpcweb && \
    node node_modules/playwright/cli.js install --with-deps chromium && \
    rm -rf /var/lib/apt/lists/*
COPY --from=process-builder /peer-process /opt/meteor-test/peer-process
CMD ["node", "/workspace/meteor/meteor-test/peer/node/build-runtime.js"]
