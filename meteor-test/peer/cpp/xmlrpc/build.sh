#!/usr/bin/env bash
# Compile the native modern C++ API instead of compiling the C peer as C++.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
mkdir -p "$output_dir"
config=${XMLRPC_C_CONFIG:-xmlrpc-c-config}
g++ -std=c++17 -O2 -Wall -Wextra -Werror \
    $("$config" c++2 client abyss-server --cflags) "$source_dir/echo_peer.cpp" \
    $("$config" c++2 client abyss-server --libs) -o "$output_dir/echo-peer"
printf 'xmlrpc-c %s\n' "$("$config" --version)" > "$output_dir/sdk-version.txt"
