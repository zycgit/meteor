#!/usr/bin/env bash
# Compile the Go peers once; scenarios execute these independent binaries.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
peer_dir=/opt/meteor-peer
prepare_only=false
while (($#)); do
    case "$1" in
        --output) peer_dir=$2; shift 2 ;;
        --prepare-only) prepare_only=true; shift ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done
mkdir -p "$peer_dir/go/order" "$peer_dir/go/compatibility"
peer_dir=$(cd -- "$peer_dir" && pwd)
output_dir="$peer_dir/go"
ready_file="$peer_dir/runtime-go.ready.json"
rm -f -- "$ready_file"
cd -- "$source_dir"

# Keep the committed module graph and the shared build cache unchanged.
names=(order-peer types-peer)
packages=(. ./types)
for protocol in grpc connect thrift; do
    for role in provider consumer; do
        names+=("order/$protocol-$role")
        packages+=("./order/$protocol/$role")
    done
done
binary_manifest=$(mktemp "$output_dir/.binaries.XXXXXX")
trap 'rm -f -- "$binary_manifest"' EXIT
for index in "${!names[@]}"; do
    name=${names[index]}
    go build -mod=readonly -trimpath -buildvcs=false -o "$output_dir/$name" "${packages[index]}"
    digest=$(sha256sum "$output_dir/$name")
    digest=${digest%% *}
    jq -nc --arg name "$name" --arg hash "$digest" '{key:$name,value:$hash}' >> "$binary_manifest"
    if [[ $name != */* ]]; then
        printf '%s  %s\n' "$digest" "$name" > "$output_dir/$name.sha256"
    fi
done

# Keep each additional SDK module graph in its own executable.
for sdk in dubbo oncrpc; do
    name="$sdk-peer"
    (cd "$source_dir/$sdk" && GOMAXPROCS=2 go build -p=2 -mod=readonly -trimpath -buildvcs=false \
        -o "$output_dir/$name" .)
    digest=$(sha256sum "$output_dir/$name")
    digest=${digest%% *}
    jq -nc --arg name "$name" --arg hash "$digest" '{key:$name,value:$hash}' >> "$binary_manifest"
done

# Each selected release owns its module graph; the persistent container is shared.
while IFS='|' read -r relative binary entry; do
    name="compatibility/$binary"
    (cd "$source_dir/compatibility/$relative" && GOMAXPROCS=2 go build -p=2 -mod=readonly \
        -trimpath -buildvcs=false -o "$output_dir/$name" "$entry")
    digest=$(sha256sum "$output_dir/$name")
    digest=${digest%% *}
    jq -nc --arg name "$name" --arg hash "$digest" '{key:$name,value:$hash}' >> "$binary_manifest"
done <<'VERSIONS'
grpc-1.85.0|grpc-1.85.0-peer|../proto/main.go
connect-1.21.1|connect-1.21.1-peer|../proto/main.go
hprose-2.0.6|hprose-2.0.6-peer|.
hprose-3.0.16|hprose-3.0.16-peer|.
thrift/v0.13.0|thrift-0.13.0-peer|.
thrift/v0.14.2|thrift-0.14.2-peer|.
thrift/v0.18.1|thrift-0.18.1-peer|.
thrift/v0.25.0|thrift-0.25.0-peer|../v0.18.1/main.go
dubbo/v1|dubbo-1.5.9-peer|.
VERSIONS

# Verify linked SDK versions rather than trusting binary names or SDK banner strings.
preparation_dir="$source_dir/../../build/preparation/sdk-versions/go"
mkdir -p "$preparation_dir"
while IFS='|' read -r binary module expected; do
    identity="$output_dir/$binary.buildinfo.txt"
    go version -m "$output_dir/$binary" > "$identity"
    actual=$(awk -v sdk="$module" '$1 == "dep" && $2 == sdk { print $3 }' "$identity")
    if [[ $actual != "$expected" ]]; then
        printf 'Wrong Go SDK in %s: %s expected %s, linked %s\n' "$binary" "$module" "$expected" "$actual" >&2
        exit 1
    fi
    mkdir -p "$preparation_dir/$(dirname "$binary")"
    cp -- "$identity" "$preparation_dir/$binary.buildinfo.txt"
    printf 'Go SDK verified: %s %s@%s\n' "$binary" "$module" "$actual"
done <<'IDENTITIES'
order-peer|google.golang.org/grpc|v1.84.0
order-peer|connectrpc.com/connect|v1.19.1
order-peer|github.com/apache/thrift|v0.25.0
dubbo-peer|dubbo.apache.org/dubbo-go/v3|v3.3.2
compatibility/grpc-1.85.0-peer|google.golang.org/grpc|v1.85.0
compatibility/connect-1.21.1-peer|connectrpc.com/connect|v1.21.1
compatibility/hprose-2.0.6-peer|github.com/hprose/hprose-golang|v2.0.6+incompatible
compatibility/hprose-3.0.16-peer|github.com/hprose/hprose-golang/v3|v3.0.16
compatibility/thrift-0.13.0-peer|github.com/apache/thrift|v0.13.0
compatibility/thrift-0.14.2-peer|github.com/apache/thrift|v0.14.2
compatibility/thrift-0.18.1-peer|github.com/apache/thrift|v0.18.1
compatibility/thrift-0.25.0-peer|github.com/apache/thrift|v0.25.0
compatibility/dubbo-1.5.9-peer|github.com/apache/dubbo-go|v1.5.9
IDENTITIES

# Publish readiness only after every executable and its identity are available.
go version > "$output_dir/go-version.txt"
cp -- "$output_dir/go-version.txt" "$preparation_dir/go-version.txt"
jq -s '{runtime:"go",binaries:from_entries}' "$binary_manifest" > "$preparation_dir/runtime-go.json"
cp -- "$preparation_dir/runtime-go.json" "$ready_file"
rm -f -- "$binary_manifest"
trap - EXIT
printf '%s\n' "$ready_file"
if [[ $prepare_only == false ]]; then
    exec sleep infinity
fi
