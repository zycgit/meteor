#!/usr/bin/env bash
# Regenerate both Connect test contracts with the locked tools prepared by the test host.
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
host_dir="$(cd "$script_dir/../../../.." && pwd)"
peer_dir="$host_dir/build/connect-peer"
order_dir="$host_dir/../met-example/src/main/resources/proto"

# Keep SDK installations in build; source directories contain only maintained test assets.
if [[ ! -x "$peer_dir/node_modules/.bin/buf" || ! -x "$peer_dir/node_modules/.bin/protoc-gen-es" ]]; then
    printf '%s\n' 'Prepare locked tools with ./gradlew :meteor-test:installConnectPeer first.' >&2
    exit 1
fi
cd "$peer_dir"

# Reuse the existing template; its local plugin path resolves from the prepared peer directory.
node_modules/.bin/buf generate "$host_dir/src/test/resources/connect/proto" \
    --template "$script_dir/buf.gen.yaml" --output "$script_dir"
node_modules/.bin/buf generate "$order_dir" --path "$order_dir/order.proto" \
    --template "$script_dir/buf.gen.yaml" --output "$script_dir"
