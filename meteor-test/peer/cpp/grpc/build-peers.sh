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
prefix="$output_dir/prefix"
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
cat > "$workspace/CMakeLists.txt" <<'CMAKE'
cmake_minimum_required(VERSION 3.22)
project(meteor_grpc_version_peers LANGUAGES C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
find_package(Protobuf CONFIG REQUIRED)
find_package(gRPC 1.85.0 EXACT CONFIG REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(PROTOBUF_C REQUIRED IMPORTED_TARGET libprotobuf-c)
add_executable(order-peer "${PEER_SOURCE_DIR}/order_peer.cpp"
    generated/order.pb.cc generated/order.grpc.pb.cc)
target_include_directories(order-peer PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/generated")
target_link_libraries(order-peer PRIVATE gRPC::grpc++ protobuf::libprotobuf)
set_target_properties(order-peer PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${PEER_OUTPUT_DIR}/cpp")
add_executable(echo-peer "${PEER_SOURCE_DIR}/../../c/grpc/echo_peer.c"
    generated/google/protobuf/wrappers.pb-c.c)
target_include_directories(echo-peer PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/generated")
target_compile_options(echo-peer PRIVATE -Werror=implicit-function-declaration)
target_link_libraries(echo-peer PRIVATE gRPC::grpc PkgConfig::PROTOBUF_C)
set_target_properties(echo-peer PROPERTIES LINKER_LANGUAGE CXX
    RUNTIME_OUTPUT_DIRECTORY "${PEER_OUTPUT_DIR}/c")
CMAKE
cmake -S "$workspace" -B "$workspace/build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH="$prefix" \
    -DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF -DCMAKE_EXPORT_NO_PACKAGE_REGISTRY=ON \
    -DPEER_SOURCE_DIR="$source_dir" -DPEER_OUTPUT_DIR="$output_dir/peer" \
    2>&1 | tee "$output_dir/peer-configure.log"
cmake --build "$workspace/build" --parallel "$build_jobs" 2>&1 | tee "$output_dir/peer-build.log"

# Preserve the exact runtime libraries loaded by each independently runnable peer.
{
    for peer in "$output_dir/peer/c/echo-peer" "$output_dir/peer/cpp/order-peer"; do
        printf '\n%s\n' "${peer#"$output_dir/"}"
        ldd "$peer"
    done
} | tee "$output_dir/peer-runtime.txt"
rm -rf -- "$workspace"
