#!/usr/bin/env bash
# Build a source release without replacing Debian's independent XML-RPC baseline.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
build_jobs=${2:-4}
mkdir -p -- "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
prefix="$output_dir/prefix"
archive="$output_dir/xmlrpc-c-1.64.04.tgz"
url=https://master.dl.sourceforge.net/project/xmlrpc-c/Xmlrpc-c%20Super%20Stable/1.64.04/xmlrpc-c-1.64.04.tgz
digest=509c3a3bffb77c81e2c364175ac70b95b799e5b695cc37d4bf833ec28fdfe0b6

# Pin the upstream archive before unpacking into the requested output directory.
curl --fail --location --show-error --connect-timeout 30 "$url" -o "$archive"
printf '%s  %s\n' "$digest" "$archive" | sha256sum --check -
mkdir -p -- "$output_dir/source" "$prefix"
tar -xzf "$archive" -C "$output_dir/source"
sdk_source="$output_dir/source/xmlrpc-c-1.64.04"
(
    cd -- "$sdk_source"
    bash ./configure --prefix="$prefix" --disable-libwww-client --disable-wininet-client \
        --disable-cgi-server --disable-abyss-openssl
    make -j "$build_jobs" SHARED_LIB_TYPE=NONE MUST_BUILD_SHLIB=N MUST_BUILD_SHLIBLE=N
    make SHARED_LIB_TYPE=NONE MUST_BUILD_SHLIB=N MUST_BUILD_SHLIBLE=N install
)

# Link both peers only against this prefix's static XML-RPC implementation.
[[ $("$prefix/bin/xmlrpc-c-config" --version) == 1.64.4 ]]
printf 'xmlrpc-c 1.64.04\nsha256 %s\nsource %s\n' "$digest" "$url" > "$output_dir/sdk-version.txt"
XMLRPC_C_CONFIG="$prefix/bin/xmlrpc-c-config" bash "$source_dir/build.sh" "$output_dir/peer/c"
XMLRPC_C_CONFIG="$prefix/bin/xmlrpc-c-config" bash "$source_dir/../../cpp/xmlrpc/build.sh" "$output_dir/peer/cpp"
