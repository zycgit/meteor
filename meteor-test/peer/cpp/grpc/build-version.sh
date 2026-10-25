#!/usr/bin/env bash
# Build the 1.85.0 C/C++ SDK and peers once, without replacing the system gRPC SDK.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
build_jobs=${2:-4}
phase=${3:-all}
if [[ ! "$build_jobs" =~ ^[1-9][0-9]*$ ]]; then
    echo "Build jobs must be a positive integer: $build_jobs" >&2
    exit 2
fi
case "$phase" in
    peer)
        exec bash "$source_dir/build-peers.sh" "$output_dir" "$build_jobs"
        ;;
    sdk|all)
        ;;
    *)
        echo "Expected sdk, peer or all build phase: $phase" >&2
        exit 2
        ;;
esac
mkdir -p "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
workspace="$output_dir/work"
prefix="$output_dir/prefix"
mkdir -p "$workspace/downloads" "$prefix" "$output_dir/peer/c" "$output_dir/peer/cpp"
export TMPDIR="$workspace/tmp"
export HOME="$workspace/home"
mkdir -p "$TMPDIR" "$HOME"

# Every archive is a release or an exact gitlink from gRPC v1.85.0, checked by content.
fetch() {
    local name=$1 repository=$2 revision=$3 digest=$4 target=$5
    local archive="$workspace/downloads/$name.tar.gz"
    local url="https://codeload.github.com/$repository/tar.gz/$revision"
    if [[ ! -f "$archive" ]]; then
        curl --fail --location --show-error "$url" -o "$archive"
    fi
    printf '%s  %s\n' "$digest" "$archive" | sha256sum --check -
    printf '%s %s\n' "$digest" "$url" >> "$output_dir/sdk-sources.txt"
    mkdir -p "$target"
    tar -xzf "$archive" --strip-components=1 -C "$target"
}

: > "$output_dir/sdk-sources.txt"
grpc_source="$workspace/grpc"
fetch grpc grpc/grpc refs/tags/v1.85.0 \
    78d933f81e66d88c9f7a5ac419aa929691ef62b61d32a92f69c16aabf21ffabe "$grpc_source"
fetch abseil-cpp abseil/abseil-cpp 5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a \
    bac569e31429f85d7bdd9f90e4767442faf9c8fa1def08a66a7d5d78e0fba204 "$grpc_source/third_party/abseil-cpp"
fetch protobuf protocolbuffers/protobuf 35cd01f9fe9afbeea38cc7b979a3b6bfcde82c03 \
    f8264c6e56b1fe59d06aa1b99ee81f76246ed2b46cf2c86b8a50f8482fa05665 "$grpc_source/third_party/protobuf"
fetch re2 google/re2 0c5616df9c0aaa44c9440d87422012423d91c7d1 \
    05a1960729d1053f170ba1ac52bd442cd47cdfbb95348f935fa1921439a9b80c "$grpc_source/third_party/re2"
fetch cares c-ares/c-ares d3a507e920e7af18a5efb7f9f1d8044ed4750013 \
    cd1968cd09d384a5c553c82bf53ba458e47e9bfcdc253a3627f1b36ea6f814e2 "$grpc_source/third_party/cares/cares"
fetch cel-c cel-expr/cel-c bbdb358f202b275731a0d7ee3162fd211886c945 \
    4ac93b6424dddebde797226c096607756b17e688efbd6f0574ff9dd3b091a76c "$grpc_source/third_party/cel-c"

# GitHub source archives retain empty submodule directories; upstream downloads absent directories.
for name in autosharding cel-spec envoy-api googleapis grpc-proto protoc-gen-validate xds; do
    directory="$grpc_source/third_party/$name"
    if [[ -d "$directory" && -z "$(ls -A -- "$directory")" ]]; then
        rmdir -- "$directory"
    fi
done

# The SDK owns its protobuf, Abseil, c-ares and RE2; OpenSSL and zlib use the image packages.
# Upstream CMake downloads additional proto-only dependencies with upstream SHA-256 checks.
cmake -S "$grpc_source" -B "$workspace/sdk-build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=17 -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_INSTALL_PREFIX="$prefix" -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF -DCMAKE_EXPORT_NO_PACKAGE_REGISTRY=ON \
    -DgRPC_INSTALL=ON -DgRPC_BUILD_TESTS=OFF -DgRPC_SSL_PROVIDER=package -DgRPC_ZLIB_PROVIDER=package \
    -DgRPC_BUILD_GRPC_CSHARP_PLUGIN=OFF -DgRPC_BUILD_GRPC_NODE_PLUGIN=OFF \
    -DgRPC_BUILD_GRPC_OBJECTIVE_C_PLUGIN=OFF -DgRPC_BUILD_GRPC_PHP_PLUGIN=OFF \
    -DgRPC_BUILD_GRPC_PYTHON_PLUGIN=OFF -DgRPC_BUILD_GRPC_RUBY_PLUGIN=OFF \
    -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_BUILD_SHARED_LIBS=OFF -Dprotobuf_WITH_ZLIB=OFF \
    -Dprotobuf_LOCAL_DEPENDENCIES_ONLY=ON 2>&1 | tee "$output_dir/sdk-configure.log"
cmake --build "$workspace/sdk-build" --target install --parallel "$build_jobs" \
    2>&1 | tee "$output_dir/sdk-build.log"

# Keep the C++ package version and Core ABI version separate in the provenance record.
export PKG_CONFIG_PATH="$prefix/lib/pkgconfig"
pkg-config --exact-version=1.85.0 grpc++
{
    printf 'grpc-release=1.85.0\ngrpc-source-commit=a83eb274517ef49e95d5529dac071f4b74abae0d\n'
    printf 'grpc-cpp-package=%s\n' "$(pkg-config --modversion grpc++)"
    printf 'grpc-core-abi-package=%s\n' "$(pkg-config --modversion grpc)"
    printf 'protobuf-package=%s\n' "$(pkg-config --modversion protobuf)"
    "$prefix/bin/protoc" --version
    cc --version | head -n 1
    cmake --version | head -n 1
} | tee "$output_dir/sdk-runtime.txt"

# Commit the completed SDK in its own Docker layer before compiling business peers.
rm -rf -- "$workspace"
if [[ "$phase" == all ]]; then
    exec bash "$source_dir/build-peers.sh" "$output_dir" "$build_jobs"
fi
