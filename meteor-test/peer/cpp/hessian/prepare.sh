#!/usr/bin/env bash
# Build unchanged upstream sources with a private, statically linked legacy OpenSSL.
set -euo pipefail

output_dir=${1:-/opt/meteor-toolchain/hessiancpp}
mkdir -p -- "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
sdk_dir="$output_dir/source-sdk"
mkdir -p -- "$sdk_dir"

# The releases predate upstream Git; release archives are pinned by SHA-256.
download() {
    local filename=$1 digest=$2 address=$3
    if [[ ! -f "$sdk_dir/$filename" ]]; then
        curl --fail --location --connect-timeout 15 --max-time 180 "$address" -o "$sdk_dir/$filename"
    fi
    printf '%s  %s\n' "$digest" "$sdk_dir/$filename" | sha256sum --check -
}
release=https://downloads.sourceforge.net/project/hessiancpp/hessiancpp/v_1_1_0
download hessiancpp.tar.gz 52f2dc9eee62d8500fbb51c4e722b1c79d2b7c2e048f47505240ec95d320b9f0 \
    "$release/hessiancpp-hessiancpp-1.1.0.tar.gz"
download sslpp.tar.gz c7c872a0b082d105ed53cf09af88c877e5bc9098b2f9b53ce7e7e1fd5a4a0801 \
    "$release/hessiancpp-sslpp-1.1.0.tar.gz"
openssl_commit=e818b74be2170fbe957a07b0da4401c2b694b3b8
download openssl.tar.gz ae081a5fb5f1a42a6e95815b642eddcb24fc0b0f67b17e524492c05cca797a82 \
    "https://codeload.github.com/openssl/openssl/tar.gz/$openssl_commit"
tar -xzf "$sdk_dir/hessiancpp.tar.gz" -C "$sdk_dir"
tar -xzf "$sdk_dir/sslpp.tar.gz" -C "$sdk_dir"
tar -xzf "$sdk_dir/openssl.tar.gz" -C "$sdk_dir"
openssl_dir="$sdk_dir/openssl-$openssl_commit"

# SSLPP always links its SSL implementation, even though this case uses HTTP only.
# Keep the obsolete API in this isolated executable; do not replace host OpenSSL.
(
    cd -- "$openssl_dir"
    ./config no-shared enable-ssl3 enable-ssl3-method
    make -j2 build_libs
)

# Compile the unchanged SDK once while building the language image.
mkdir -p -- "$output_dir/objects"
for sdk_source in "$sdk_dir"/hessiancpp/{zlibdec,hessian_input,hessian_output,string_input_stream,hessian_proxy,wrappers}.cpp \
                  "$sdk_dir"/sslpp/{http_connection,http_exception,ssl_connection,socket_connection}.cpp; do
    object_file="$output_dir/objects/$(basename -- "${sdk_source%.cpp}").o"
    g++ -std=c++11 -O2 -Wno-deprecated -D_POSIX_THREAD_SAFE_FUNCTIONS -D_REENTRANT \
        -I "$sdk_dir/hessiancpp" -I "$sdk_dir/sslpp" -I "$openssl_dir/include" \
        -c "$sdk_source" -o "$object_file"
done
ar rcs "$output_dir/libhessian-sdk.a" "$output_dir"/objects/*.o
printf 'hessiancpp 1.1.0\nOpenSSL 1.0.2u %s (private static build; HTTP only)\n' \
    "$openssl_commit" > "$output_dir/sdk-version.txt"
