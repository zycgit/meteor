#!/usr/bin/env bash
# Build Apache Thrift 0.25.0 beside the system SDK, including its own generators and runtimes.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
build_jobs=${2:-4}
mkdir -p "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
sdk_prefix="$output_dir/install"
workspace="$output_dir/source"
mkdir -p "$workspace"

# Pin the official release archive, not a moving branch or the installed Debian version.
source_url=https://archive.apache.org/dist/thrift/0.25.0/thrift-0.25.0.tar.gz
source_sha256=66da4707214c54c94bac082103dc67adaf9e08925662700f269170a7b534b214
curl --fail --location --show-error "$source_url" -o "$workspace/thrift-0.25.0.tar.gz"
printf '%s  %s\n' "$source_sha256" "$workspace/thrift-0.25.0.tar.gz" | sha256sum --check -
printf '%s %s\n' "$source_sha256" "$source_url" > "$output_dir/sdk-sources.txt"
tar -xzf "$workspace/thrift-0.25.0.tar.gz" -C "$workspace"

# Compile only the C/C++ languages used by these peers, with no extra runtime or test downloads.
compiler_options=()
for language in CL D DART DELPHI ERL GO GV HAXE HTML MARKDOWN MMD JAVA JAVAME JS JSON KOTLIN LUA \
    NETSTD OCAML PERL PHP PY RB RS ST XML XSD; do
    compiler_options+=("-DTHRIFT_COMPILER_$language=OFF")
done
cmake -S "$workspace/thrift-0.25.0" -B "$output_dir/build-sdk" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$sdk_prefix" \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_TESTING=OFF -DBUILD_TUTORIALS=OFF -DWITH_CPP=ON -DWITH_C_GLIB=ON \
    -DWITH_JAVA=OFF -DWITH_JAVASCRIPT=OFF -DWITH_KOTLIN=OFF -DWITH_NODEJS=OFF -DWITH_PYTHON=OFF \
    -DWITH_QT5=OFF -DWITH_LIBEVENT=OFF -DWITH_ZLIB=OFF -DWITH_OPENSSL=OFF \
    "${compiler_options[@]}"
cmake --build "$output_dir/build-sdk" --target install --parallel "$build_jobs"

# Reuse the same peer sources while keeping generated code and the matching library together.
test -f "$sdk_prefix/lib/libthrift.so"
test -f "$sdk_prefix/lib/libthrift_c_glib.so"
bash "$source_dir/build.sh" "$output_dir/peer/cpp" "$sdk_prefix"
bash "$source_dir/../../c/thrift/build.sh" "$output_dir/peer/c" "$sdk_prefix"
"$sdk_prefix/bin/thrift" --version | tee "$output_dir/sdk-version.txt"
test "$("$sdk_prefix/bin/thrift" --version)" = 'Thrift version 0.25.0'
ldd "$output_dir/peer/cpp/order-peer" > "$output_dir/peer/cpp/ldd.txt"
ldd "$output_dir/peer/c/echo-peer" > "$output_dir/peer/c/ldd.txt"
grep -F "$sdk_prefix/lib/libthrift.so" "$output_dir/peer/cpp/ldd.txt"
grep -F "$sdk_prefix/lib/libthrift_c_glib.so" "$output_dir/peer/c/ldd.txt"
