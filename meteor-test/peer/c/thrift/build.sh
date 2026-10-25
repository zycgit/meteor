#!/usr/bin/env bash
# Generate and compile the official Apache Thrift C GLib single-string echo peer.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
sdk_prefix=${2:-}
contract="$source_dir/../../../src/test/resources/example/contracts/thrift/composition.thrift"
mkdir -p "$output_dir"
compiler=thrift
sdk_flags=()
if [[ -n "$sdk_prefix" ]]; then
    # CMake installs the C GLib runtime without a thrift_c_glib.pc file.
    compiler="$sdk_prefix/bin/thrift"
    read -r -a sdk_flags <<< "$(pkg-config --cflags --libs glib-2.0 gobject-2.0)"
    sdk_flags+=(-I "$sdk_prefix/include" -L "$sdk_prefix/lib" "-Wl,-rpath,$sdk_prefix/lib" -lthrift_c_glib)
else
    read -r -a sdk_flags <<< "$(pkg-config --cflags --libs thrift_c_glib)"
fi
"$compiler" --gen c_glib -out "$output_dir" "$contract"
gcc -std=c11 -O2 -Wall -Wextra -I "$output_dir" \
    "$source_dir/echo_peer.c" "$output_dir/echo_service.c" "$output_dir/composition_types.c" \
    "${sdk_flags[@]}" -o "$output_dir/echo-peer"
