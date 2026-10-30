ARG DEBIAN_IMAGE=debian:bookworm-slim
FROM ${DEBIAN_IMAGE} AS loams-sdk
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && \
    sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources && \
    apt-get update && apt-get install -y --no-install-recommends \
        bash curl g++ cmake ninja-build pkg-config libssl-dev make xz-utils && \
    rm -rf /var/lib/apt/lists/*
COPY meteor-test/peer/cpp/connect/build-sdk.sh /build/build-sdk.sh
RUN bash /build/build-sdk.sh /opt/meteor-loams

FROM ${DEBIAN_IMAGE} AS process-builder
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev && rm -rf /var/lib/apt/lists/*
COPY meteor-test/src/main/c/peer-process.c /tmp/peer_process.c
RUN gcc -std=c11 -O2 -Wall -Wextra -Werror -static /tmp/peer_process.c -o /peer-process
FROM ${DEBIAN_IMAGE} AS native-base
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && \
    sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        bash jq gcc g++ make libc6-dev libtirpc-dev rpcsvc-proto ca-certificates \
        libgrpc++-dev libprotobuf-dev protobuf-compiler protobuf-compiler-grpc \
        pkg-config libthrift-dev thrift-compiler && \
    rm -rf /var/lib/apt/lists/*
# Additional C/C++ HTTP SDKs share the same compiler toolchain.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libboost-dev libxmlrpc-core-c3-dev libxmlrpc-c++8-dev libcurl4-openssl-dev gsoap libgsoap-dev && \
    rm -rf /var/lib/apt/lists/*
# These RPC SDKs use the same native process runner and independent generated bindings.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libjsonrpccpp-dev libthrift-c-glib-dev libglib2.0-dev \
        libprotobuf-c-dev protobuf-c-compiler && \
    rm -rf /var/lib/apt/lists/*
# Source-only clients are fetched and verified while building the image.
RUN apt-get update && apt-get install -y --no-install-recommends curl perl zlib1g-dev && \
    rm -rf /var/lib/apt/lists/*
RUN mkdir -p /opt/meteor-toolchain/hprose-cpp /opt/meteor-toolchain/jsonrpc-c && \
    curl -fsSL https://codeload.github.com/hprose/hprose-cpp1x/tar.gz/086de4cb210ee4153fabf7bdc414a3c68b516887 \
        -o /tmp/hprose-cpp.tar.gz && \
    echo '98e31e05783d512973194cb9ec4732cba493377e8d9cf5475a754fc6ee9ce511  /tmp/hprose-cpp.tar.gz' | sha256sum -c - && \
    tar -xzf /tmp/hprose-cpp.tar.gz --strip-components=1 -C /opt/meteor-toolchain/hprose-cpp && \
    curl -fsSL https://codeload.github.com/initlevel5/json-rpc-client/tar.gz/52d7b9068478e08d54407f4a85203093b8e9cc74 \
        -o /tmp/jsonrpc-c.tar.gz && \
    echo '2618994da70c74f346fb24ddbccfe05f01bdd531558e7e94fe53b7d27937179e  /tmp/jsonrpc-c.tar.gz' | sha256sum -c - && \
    tar -xzf /tmp/jsonrpc-c.tar.gz --strip-components=1 -C /opt/meteor-toolchain/jsonrpc-c && \
    rm /tmp/hprose-cpp.tar.gz /tmp/jsonrpc-c.tar.gz
COPY meteor-test/peer/cpp/hessian/prepare.sh /build/prepare-hessian.sh
RUN bash /build/prepare-hessian.sh /opt/meteor-toolchain/hessiancpp
COPY --from=loams-sdk /opt/meteor-loams /opt/meteor-loams
COPY --from=process-builder /peer-process /opt/meteor-test/peer-process
WORKDIR /workspace/meteor
ENTRYPOINT []
CMD ["bash", "/workspace/meteor/meteor-test/peer/c/build-runtime.sh"]

# Every additional release has a private prefix; system SDKs remain the baseline.
FROM native-base AS native-sdk-tools
RUN apt-get update && apt-get install -y --no-install-recommends \
        cmake ninja-build flex bison unzip bzip2 libssl-dev zlib1g-dev \
        libjsoncpp-dev libmicrohttpd-dev && rm -rf /var/lib/apt/lists/*

# Independent build stages preserve SDK caches and run only while building the image.
FROM native-sdk-tools AS grpc-latest
COPY meteor-test/peer/cpp/grpc/build-version.sh /build/meteor-test/peer/cpp/grpc/build-version.sh
RUN bash /build/meteor-test/peer/cpp/grpc/build-version.sh \
    /opt/meteor-toolchain/sdk-versions/grpc/1.85.0 8 sdk
COPY meteor-test/peer/cpp/grpc/build-peers.sh meteor-test/peer/cpp/grpc/order_peer.cpp /build/meteor-test/peer/cpp/grpc/
COPY meteor-test/peer/c/grpc /build/meteor-test/peer/c/grpc
COPY meteor-test/resource/protocol/proto /build/meteor-test/resource/protocol/proto
RUN bash /build/meteor-test/peer/cpp/grpc/build-version.sh \
    /opt/meteor-toolchain/sdk-versions/grpc/1.85.0 8 peer

FROM native-sdk-tools AS thrift-latest
COPY meteor-test/peer/cpp/thrift /build/meteor-test/peer/cpp/thrift
COPY meteor-test/peer/c/thrift /build/meteor-test/peer/c/thrift
COPY meteor-test/resource/protocol/thrift /build/meteor-test/resource/protocol/thrift
RUN bash /build/meteor-test/peer/cpp/thrift/build-version.sh \
    /opt/meteor-toolchain/sdk-versions/thrift/0.25.0 4 && \
    rm -rf /opt/meteor-toolchain/sdk-versions/thrift/0.25.0/source \
        /opt/meteor-toolchain/sdk-versions/thrift/0.25.0/build-sdk

FROM native-sdk-tools AS soap-latest
COPY meteor-test/peer/c/soap /build/meteor-test/peer/c/soap
COPY meteor-test/peer/cpp/soap /build/meteor-test/peer/cpp/soap
COPY meteor-test/resource/protocol/wsdl /build/meteor-test/resource/protocol/wsdl
COPY meteor-test/resource/protocol/xsd /build/meteor-test/resource/protocol/xsd
RUN bash /build/meteor-test/peer/c/soap/build-version.sh \
    /opt/meteor-toolchain/sdk-versions/soap/2.8.146 4

FROM native-sdk-tools AS xmlrpc-latest
COPY meteor-test/peer/c/xmlrpc /build/meteor-test/peer/c/xmlrpc
COPY meteor-test/peer/cpp/xmlrpc /build/meteor-test/peer/cpp/xmlrpc
RUN bash /build/meteor-test/peer/c/xmlrpc/build-version.sh \
    /opt/meteor-toolchain/sdk-versions/xmlrpc/1.64.04 4

FROM native-sdk-tools AS oncrpc-latest
COPY meteor-test/peer/c/build-version.sh /build/meteor-test/peer/c/build-version.sh
COPY meteor-test/peer/c/order /build/meteor-test/peer/c/order
COPY meteor-test/peer/cpp/order /build/meteor-test/peer/cpp/order
COPY meteor-test/resource/protocol/x /build/meteor-test/resource/protocol/x
RUN bash /build/meteor-test/peer/c/build-version.sh \
    /opt/meteor-toolchain/sdk-versions/oncrpc/1.3.8 4 && \
    rm -rf /opt/meteor-toolchain/sdk-versions/oncrpc/1.3.8/libtirpc-1.3.8 \
        /opt/meteor-toolchain/sdk-versions/oncrpc/1.3.8/libtirpc-1.3.8.tar.bz2

FROM native-sdk-tools AS jsonrpc-latest
COPY meteor-test/peer/cpp/jsonrpc /build/meteor-test/peer/cpp/jsonrpc
RUN bash /build/meteor-test/peer/cpp/jsonrpc/build-version.sh \
    /opt/meteor-toolchain/sdk-versions/jsonrpc/1.4.1 4 && \
    rm -rf /opt/meteor-toolchain/sdk-versions/jsonrpc/1.4.1/source \
        /opt/meteor-toolchain/sdk-versions/jsonrpc/1.4.1/build-sdk

# The test still runs one native container, with one executable tree per SDK release.
FROM native-base AS native
COPY --from=grpc-latest /opt/meteor-toolchain/sdk-versions/grpc /opt/meteor-toolchain/sdk-versions/grpc
COPY --from=thrift-latest /opt/meteor-toolchain/sdk-versions/thrift /opt/meteor-toolchain/sdk-versions/thrift
COPY --from=soap-latest /opt/meteor-toolchain/sdk-versions/soap /opt/meteor-toolchain/sdk-versions/soap
COPY --from=xmlrpc-latest /opt/meteor-toolchain/sdk-versions/xmlrpc /opt/meteor-toolchain/sdk-versions/xmlrpc
COPY --from=oncrpc-latest /opt/meteor-toolchain/sdk-versions/oncrpc /opt/meteor-toolchain/sdk-versions/oncrpc
COPY --from=jsonrpc-latest /opt/meteor-toolchain/sdk-versions/jsonrpc /opt/meteor-toolchain/sdk-versions/jsonrpc
