#!/usr/bin/env bash
# Compile the unmodified official C++ 1x source SDK at a fixed commit.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
commit=086de4cb210ee4153fabf7bdc414a3c68b516887
archive_sha256=98e31e05783d512973194cb9ec4732cba493377e8d9cf5475a754fc6ee9ce511
sdk_dir=${HPROSE_CPP_SDK_DIR:-/opt/meteor-toolchain/hprose-cpp}
if [[ ! -f $sdk_dir/hprose/rpc/Client.h ]]; then
    printf 'Missing official Hprose C++ SDK sources: %s\n' "$sdk_dir" >&2
    exit 1
fi
mkdir -p "$output_dir"

# Only the public RPC client and codec are required for its documented transport extension point.
mapfile -t sdk_sources < <(find "$sdk_dir/hprose" -type f -name '*.cpp' \
    ! -path '*/test/*' ! -path '*/http/*' ! -path '*/rpc/asio/*' | sort)
g++ -std=c++11 -O2 -Wall -Wextra -pthread -I "$sdk_dir" \
    $(pkg-config --cflags libcurl) "$source_dir/echo_peer.cpp" "${sdk_sources[@]}" \
    $(pkg-config --libs libcurl) -o "$output_dir/echo-peer"
printf 'hprose/hprose-cpp1x source commit %s\narchive SHA256 %s\n' \
    "$commit" "$archive_sha256" > "$output_dir/sdk-version.txt"
printf 'Public Client::sendAndReceive transport extension: libcurl %s\n' \
    "$(pkg-config --modversion libcurl)" >> "$output_dir/sdk-version.txt"
