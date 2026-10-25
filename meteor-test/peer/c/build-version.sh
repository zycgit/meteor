#!/usr/bin/env bash
# Build one upstream libtirpc release and independent C/C++ order peers.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
jobs=${2:-4}
mkdir -p "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
prefix="$output_dir/prefix"
archive="$output_dir/libtirpc-1.3.8.tar.bz2"
url=https://downloads.sourceforge.net/project/libtirpc/libtirpc/1.3.8/libtirpc-1.3.8.tar.bz2
digest=8839959bfcc7a0f4c609d8e4f53f1c67ae33de23775ec35beb39ff15adf11920

# Keep source identity, headers and the linked static library in this private tree.
curl --fail --location --show-error --connect-timeout 20 --max-time 180 "$url" -o "$archive"
printf '%s  %s\n' "$digest" "$archive" | sha256sum --check -
tar -xjf "$archive" -C "$output_dir"
cd "$output_dir/libtirpc-1.3.8"
./configure --prefix="$prefix" --libdir="$prefix/lib" --disable-gssapi --disable-shared --enable-static
make --jobs="$jobs"
make install

# rpcgen comes from the shared toolchain; the SDK headers and library are release-specific.
make -C "$source_dir/order" "BUILD_DIR=$output_dir/peer" \
    "TIRPC_INCLUDE=$prefix/include/tirpc" "LDLIBS=$prefix/lib/libtirpc.a -pthread"
PKG_CONFIG_PATH="$prefix/lib/pkgconfig" pkg-config --exact-version=1.3.8 libtirpc
printf 'libtirpc %s\n' "$(PKG_CONFIG_PATH="$prefix/lib/pkgconfig" pkg-config --modversion libtirpc)" \
    > "$output_dir/sdk-version.txt"
printf '%s %s\n' "$digest" "$url" > "$output_dir/sdk-sources.txt"
sha256sum "$prefix/lib/libtirpc.a" >> "$output_dir/sdk-sources.txt"
for language in c cpp; do
    ldd "$output_dir/peer/$language/provider" > "$output_dir/peer/$language/provider-libraries.txt"
    ldd "$output_dir/peer/$language/consumer" > "$output_dir/peer/$language/consumer-libraries.txt"
done
