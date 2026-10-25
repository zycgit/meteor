#!/usr/bin/env bash
# Build the unmodified upstream C JSON-RPC HTTP client at a fixed source commit.
set -euo pipefail

output_dir=${1:?Output directory is required}
commit=52d7b9068478e08d54407f4a85203093b8e9cc74
archive_sha256=2618994da70c74f346fb24ddbccfe05f01bdd531558e7e94fe53b7d27937179e
sdk_dir=${METEOR_JSONRPC_C_SDK_DIR:-/opt/meteor-toolchain/jsonrpc-c}
if [[ ! -f $sdk_dir/json_rpc.c || ! -f $sdk_dir/main.c ]]; then
    printf 'Missing fixed JSON-RPC C SDK sources: %s\n' "$sdk_dir" >&2
    exit 1
fi
mkdir -p "$output_dir"

# The image owns SDK acquisition; runtime compilation stays offline and rejects altered source.
printf '%s  %s\n' \
    32a95e57179ea228644f3dbe09047e81b3aa1f9c35db99e7dc07d4607d556b8f "$sdk_dir/main.c" \
    f8157582945a50fa73146496519622bbde01b382abfc6c25ca5a1b24ae1620f0 "$sdk_dir/json_rpc.c" \
    e27ae5ca94de6fffd6e23c732ae60a941b2f7d51cd166557e3b8dd65219fd50f "$sdk_dir/json_rpc.h" \
    | sha256sum -c -

# Upstream owns request serialization, HTTP transport and the standalone CLI.
gcc -std=gnu99 -O2 -Wall -Wextra -I "$sdk_dir" \
    "$sdk_dir/main.c" "$sdk_dir/json_rpc.c" -o "$output_dir/client"
printf 'initlevel5/json-rpc-client source commit %s\narchive SHA256 %s\n' \
    "$commit" "$archive_sha256" > "$output_dir/sdk-version.txt"
