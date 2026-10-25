#!/usr/bin/env bash
# Compile the XML-RPC for C SDK peer into the shared native output volume.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
mkdir -p "$output_dir"
config=${XMLRPC_C_CONFIG:-xmlrpc-c-config}
gcc -std=c11 -O2 -Wall -Wextra -Werror \
    $("$config" client abyss-server --cflags) "$source_dir/echo_peer.c" \
    $("$config" client abyss-server --libs) -o "$output_dir/echo-peer"
printf 'xmlrpc-c %s\n' "$("$config" --version)" > "$output_dir/sdk-version.txt"
