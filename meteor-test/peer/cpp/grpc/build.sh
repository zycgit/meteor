#!/usr/bin/env bash
# Generate the test-owned contract and compile the official C++ gRPC peer in the output volume.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
contract_dir="$source_dir/../../../src/test/resources/order"
mkdir -p "$output_dir"
protoc -I "$contract_dir" --cpp_out="$output_dir" --grpc_out="$output_dir" \
    --plugin=protoc-gen-grpc="$(command -v grpc_cpp_plugin)" "$contract_dir/order.proto"
g++ -std=c++17 -O2 -Wall -Wextra -pthread -I "$output_dir" \
    "$source_dir/order_peer.cpp" "$output_dir/order.pb.cc" "$output_dir/order.grpc.pb.cc" \
    $(pkg-config --cflags --libs grpc++ protobuf) -o "$output_dir/order-peer"
