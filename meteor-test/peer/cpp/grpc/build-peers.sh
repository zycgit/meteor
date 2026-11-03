#!/usr/bin/env bash
# Compile the unchanged business peers against the SDK installed by build-version.sh.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
build_jobs=${2:-4}
if [[ ! "$build_jobs" =~ ^[1-9][0-9]*$ ]]; then
    echo "Build jobs must be a positive integer: $build_jobs" >&2
    exit 2
fi
output_dir=$(cd -- "$output_dir" && pwd)
prefix=${3:-"$output_dir/prefix"}
test -f "$prefix/lib/pkgconfig/grpc++.pc" || {
    printf 'Missing prepared gRPC SDK prefix: %s\n' "$prefix" >&2
    exit 2
}
workspace="$output_dir/peer-work"
export TMPDIR="$workspace/tmp"
export HOME="$workspace/home"
mkdir -p "$TMPDIR" "$HOME" "$output_dir/peer/c" "$output_dir/peer/cpp"

# Only generated bindings belong to this phase; SDK archives and libraries remain untouched.
contract_dir="$source_dir/../../../resource/protocol/proto"
generated="$workspace/generated"
mkdir -p "$generated"
"$prefix/bin/protoc" -I "$contract_dir" --cpp_out="$generated" --grpc_out="$generated" \
    --plugin="protoc-gen-grpc=$prefix/bin/grpc_cpp_plugin" "$contract_dir/order.proto"
protoc-c -I /usr/include --c_out="$generated" /usr/include/google/protobuf/wrappers.proto
# Runtime images contain compilers and installed SDK facts, without the SDK's CMake toolchain.
export PKG_CONFIG_PATH="$prefix/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
pkg-config --exact-version=1.85.0 grpc++
read -r -a cpp_flags <<< "$(pkg-config --cflags grpc++ protobuf)"
read -r -a cpp_libraries <<< "$(pkg-config --static --libs grpc++ protobuf)"
read -r -a c_flags <<< "$(pkg-config --cflags grpc libprotobuf-c)"
read -r -a c_libraries <<< "$(pkg-config --static --libs grpc libprotobuf-c)"
{
    # A linker group resolves the private static SDK graph without falling back to system gRPC.
    g++ -std=c++17 -O2 -Wall -Wextra -pthread -I "$generated" "${cpp_flags[@]}" \
        "$source_dir/order_peer.cpp" "$generated/order.pb.cc" "$generated/order.grpc.pb.cc" \
        -Wl,--start-group "${cpp_libraries[@]}" -Wl,--end-group -ldl -lm -lrt \
        -o "$output_dir/peer/cpp/order-peer"
    gcc -std=c11 -O2 -Wall -Wextra -Werror=implicit-function-declaration -pthread \
        -I "$generated" "${c_flags[@]}" -c "$source_dir/../../c/grpc/echo_peer.c" -o "$workspace/echo-peer.o"
    gcc -std=c11 -O2 -Wall -Wextra -I "$generated" "${c_flags[@]}" \
        -c "$generated/google/protobuf/wrappers.pb-c.c" -o "$workspace/wrappers.pb-c.o"
    g++ -pthread "$workspace/echo-peer.o" "$workspace/wrappers.pb-c.o" \
        -Wl,--start-group "${c_libraries[@]}" -Wl,--end-group -ldl -lm -lrt -o "$output_dir/peer/c/echo-peer"
} 2>&1 | tee "$output_dir/peer-build.log"

# Preserve the exact runtime libraries loaded by each independently runnable peer.
{
    for peer in "$output_dir/peer/c/echo-peer" "$output_dir/peer/cpp/order-peer"; do
        printf '\n%s\n' "${peer#"$output_dir/"}"
        ldd "$peer"
    done
} | tee "$output_dir/peer-runtime.txt"
rm -rf -- "$workspace"
