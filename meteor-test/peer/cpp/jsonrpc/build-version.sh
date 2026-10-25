#!/usr/bin/env bash
# Build libjson-rpc-cpp 1.4.1 in a private prefix; keep the Debian 0.7 runtime untouched.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
build_jobs=${2:-4}
mkdir -p "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
sdk_prefix="$output_dir/install"
workspace="$output_dir/source"
mkdir -p "$workspace"

# The upstream release tag is fixed by content; no test-time package resolution is used.
source_url=https://codeload.github.com/cinemast/libjson-rpc-cpp/tar.gz/refs/tags/v1.4.1
source_sha256=7a057e50d6203e4ea0a10ba5e4dbf344c48b177e5a3bf82e850eb3a783c11eb5
curl --fail --location --show-error "$source_url" -o "$workspace/libjson-rpc-cpp-1.4.1.tar.gz"
printf '%s  %s\n' "$source_sha256" "$workspace/libjson-rpc-cpp-1.4.1.tar.gz" | sha256sum --check -
printf '%s %s\n' "$source_sha256" "$source_url" > "$output_dir/sdk-sources.txt"
tar -xzf "$workspace/libjson-rpc-cpp-1.4.1.tar.gz" -C "$workspace"

# Use only the upstream HTTP client/server and the container's ordinary native dependencies.
cmake -S "$workspace/libjson-rpc-cpp-1.4.1" -B "$output_dir/build-sdk" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$sdk_prefix" -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_INSTALL_RPATH="$sdk_prefix/lib" -DCMAKE_CXX_STANDARD=14 \
    -DBUILD_SHARED_LIBS=ON -DBUILD_STATIC_LIBS=OFF -DHUNTER_ENABLED=OFF -DWITH_COVERAGE=OFF \
    -DCOMPILE_TESTS=OFF -DCOMPILE_EXAMPLES=OFF -DCOMPILE_STUBGEN=OFF \
    -DHTTP_CLIENT=ON -DHTTP_SERVER=ON -DREDIS_CLIENT=OFF -DREDIS_SERVER=OFF
cmake --build "$output_dir/build-sdk" --target install --parallel "$build_jobs"

# The original echo peer is linked against this release; RPATH preserves runtime isolation.
bash "$source_dir/build.sh" "$output_dir/peer/cpp" "$sdk_prefix"
PKG_CONFIG_PATH="$sdk_prefix/lib/pkgconfig" pkg-config --exact-version=1.4.1 libjsonrpccpp-client
printf 'libjson-rpc-cpp 1.4.1\n' > "$output_dir/sdk-version.txt"
ldd "$output_dir/peer/cpp/echo-peer" > "$output_dir/peer/cpp/ldd.txt"
grep -F "$sdk_prefix/lib/libjsonrpccpp-client.so" "$output_dir/peer/cpp/ldd.txt"
grep -F "$sdk_prefix/lib/libjsonrpccpp-server.so" "$output_dir/peer/cpp/ldd.txt"
