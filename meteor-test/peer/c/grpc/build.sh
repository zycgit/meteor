#!/usr/bin/env bash
# Compile the public gRPC Core C API peer and generated Protobuf-C StringValue codec.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
mkdir -p "$output_dir"
protoc-c -I /usr/include --c_out="$output_dir" /usr/include/google/protobuf/wrappers.proto
gcc -std=c11 -O2 -Wall -Wextra -Werror -pthread -I "$output_dir" \
    "$source_dir/echo_peer.c" "$output_dir/google/protobuf/wrappers.pb-c.c" \
    $(pkg-config --cflags --libs grpc libprotobuf-c) -o "$output_dir/echo-peer"
