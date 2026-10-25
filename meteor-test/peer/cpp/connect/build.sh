#!/usr/bin/env bash
# Only this peer sees the source SDK's protobuf 36.1 and curl 8.22 static libraries.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
sdk_prefix=${METEOR_LOAMS_SDK_PREFIX:-/opt/meteor-loams}
export PKG_CONFIG_PATH="$sdk_prefix/lib/pkgconfig"
pkg-config --exact-version=36.1.0 protobuf
pkg-config --exact-version=8.22.0 libcurl
mkdir -p "$output_dir"
g++ -std=c++17 -O2 -Wall -Wextra -Werror -pthread \
    -I "$sdk_prefix/include" -I "$sdk_prefix/include/loams-gen" \
    $(pkg-config --cflags protobuf libcurl) "$source_dir/echo_peer.cpp" \
    -L "$sdk_prefix/lib" -lloams -lloams_gen \
    $(pkg-config --static --libs protobuf libcurl) -o "$output_dir/echo-peer"
