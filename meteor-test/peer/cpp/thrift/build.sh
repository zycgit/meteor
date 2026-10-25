#!/usr/bin/env bash
# Generate the test-owned contract and compile the official C++ Thrift peer in the output volume.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
sdk_prefix=${2:-}
contract="$source_dir/../../../src/test/resources/order/order.thrift"
mkdir -p "$output_dir"
compiler=thrift
sdk_flags=()
if [[ -n "$sdk_prefix" ]]; then
    # The generator, headers and runtime must all come from the selected release.
    compiler="$sdk_prefix/bin/thrift"
    sdk_flags=(-I "$sdk_prefix/include" -L "$sdk_prefix/lib" "-Wl,-rpath,$sdk_prefix/lib" -lthrift)
else
    read -r -a sdk_flags <<< "$(pkg-config --cflags --libs thrift)"
fi
"$compiler" --gen cpp:no_skeleton -out "$output_dir" "$contract"
g++ -std=c++17 -O2 -Wall -Wextra -pthread -I "$output_dir" \
    "$source_dir/order_peer.cpp" "$output_dir/order_types.cpp" "$output_dir/OrderThriftService.cpp" \
    "${sdk_flags[@]}" -o "$output_dir/order-peer"
