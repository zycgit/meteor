#!/usr/bin/env bash
# Build the unmodified source release and its exact protobuf runtime in a private prefix.
set -euo pipefail

prefix=${1:?SDK installation prefix is required}
build_jobs=${LOAMS_BUILD_JOBS:-4}
workspace=$(mktemp -d)
trap 'rm -rf "$workspace"' EXIT
mkdir -p "$prefix"

fetch() {
    local url=$1 archive=$2 digest=$3
    curl --fail --location --show-error "$url" -o "$workspace/$archive"
    printf '%s  %s\n' "$digest" "$workspace/$archive" | sha256sum --check -
    printf '%s %s\n' "$digest" "$url" >> "$prefix/sdk-sources.txt"
}

# Release archives are pinned by content, including upstream protobuf's Abseil version.
: > "$prefix/sdk-sources.txt"
fetch https://github.com/abseil/abseil-cpp/releases/download/20250512.1/abseil-cpp-20250512.1.tar.gz \
    abseil.tar.gz 9b7a064305e9fd94d124ffa6cc358592eb42b5da588fb4e07d09254aa40086db
fetch https://github.com/protocolbuffers/protobuf/releases/download/v36.1/protobuf-36.1.tar.gz \
    protobuf.tar.gz dc74fa582f559cbd31614ddfefb4868f43c919d7184bde514bb47f90c6025eb8
fetch https://curl.se/download/curl-8.22.0.tar.xz \
    curl.tar.xz f7ef3ae8a22e521f289803fe93543eb64c329b58aa73a9e224dfd915a2a5f4f7
# The C++ source archive belongs to the same pinned upstream release as its headers.
fetch https://github.com/ostrium-labs/loams/releases/download/v0.1.3/loams-cpp-0.1.3.tar.gz \
    loams.tar.gz 19a45e52fee229778d13b7930656b9c4541a8f941e3d2aa55eb328c9fd9f9875
tar -xzf "$workspace/abseil.tar.gz" -C "$workspace"
tar -xzf "$workspace/protobuf.tar.gz" -C "$workspace"
tar -xJf "$workspace/curl.tar.xz" -C "$workspace"
tar -xzf "$workspace/loams.tar.gz" -C "$workspace"

# All SDK libraries are static; native's system protobuf and libcurl are untouched.
cmake -S "$workspace/abseil-cpp-20250512.1" -B "$workspace/abseil-build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=17 -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_INSTALL_PREFIX="$prefix" -DBUILD_SHARED_LIBS=OFF -DABSL_ENABLE_INSTALL=ON
cmake --build "$workspace/abseil-build" --target install --parallel "$build_jobs"
cmake -S "$workspace/protobuf-36.1" -B "$workspace/protobuf-build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=17 -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_PREFIX_PATH="$prefix" \
    -Dprotobuf_BUILD_SHARED_LIBS=OFF -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_BUILD_PROTOC_BINARIES=OFF \
    -Dprotobuf_BUILD_LIBPROTOC=OFF -Dprotobuf_WITH_ZLIB=OFF -Dprotobuf_LOCAL_DEPENDENCIES_ONLY=ON
cmake --build "$workspace/protobuf-build" --target install --parallel "$build_jobs"
cmake -S "$workspace/curl-8.22.0" -B "$workspace/curl-build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_INSTALL_LIBDIR=lib \
    -DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC_LIBS=ON -DBUILD_CURL_EXE=OFF -DBUILD_TESTING=OFF \
    -DHTTP_ONLY=ON -DCURL_USE_OPENSSL=ON -DCURL_USE_LIBPSL=OFF -DUSE_LIBIDN2=OFF \
    -DCURL_ZLIB=OFF -DCURL_BROTLI=OFF -DCURL_ZSTD=OFF
cmake --build "$workspace/curl-build" --target install --parallel "$build_jobs"

# The SDK uses its original CMake project and committed generated bindings.
export PKG_CONFIG_PATH="$prefix/lib/pkgconfig"
cmake -S "$workspace/loams-cpp-0.1.3" -B "$workspace/loams-build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_PREFIX_PATH="$prefix" -DLOAMS_BUILD_TESTS=OFF -DLOAMS_BUILD_EXAMPLES=OFF
cmake --build "$workspace/loams-build" --target install --parallel "$build_jobs"
pkg-config --exact-version=36.1.0 protobuf
pkg-config --exact-version=8.22.0 libcurl
