#!/usr/bin/env bash
# The image owns the SDK and its legacy toolchain; runtime compilation is offline.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
toolchain_dir=${HESSIAN_CPP_SDK_ROOT:-/opt/meteor-toolchain/hessiancpp}
openssl_dir="$toolchain_dir/source-sdk/openssl-e818b74be2170fbe957a07b0da4401c2b694b3b8"
mkdir -p -- "$output_dir"

# C++11 retains upstream dynamic exception and auto_ptr APIs without SDK patches.
g++ -std=c++11 -O2 -Wno-deprecated -I "$toolchain_dir/source-sdk/hessiancpp" \
    -I "$toolchain_dir/source-sdk/sslpp" -I "$openssl_dir/include" "$source_dir/consumer.cpp" \
    "$toolchain_dir/libhessian-sdk.a" "$openssl_dir/libssl.a" "$openssl_dir/libcrypto.a" \
    -lz -ldl -pthread -o "$output_dir/consumer"
cp -- "$toolchain_dir/sdk-version.txt" "$output_dir/sdk-version.txt"
