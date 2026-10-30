#!/usr/bin/env bash
# Regenerate or verify the official rpcgen files from the test-owned contract.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
check=false
if [[ ${1:-} == --check ]]; then check=true; shift; fi
(($# == 0)) || { printf 'Usage: %s [--check]\n' "$0" >&2; exit 2; }
command -v rpcgen >/dev/null
temporary_root="$source_dir/../../build/tmp/oncrpc-rpcgen"
mkdir -p -- "$temporary_root"
temporary=$(mktemp -d "$temporary_root/run.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT
cp "$source_dir/../../resource/protocol/x/interop.x" "$temporary/interop.x"
cd -- "$temporary"
options=(-h -c -l -m)
names=(interop.h interop_xdr.c interop_clnt.c interop_svc.c)
for index in "${!names[@]}"; do
    name=${names[index]}
    timeout 30 rpcgen "${options[index]}" interop.x > "$name"
    if [[ $check == true ]]; then
        cmp "$source_dir/generated/$name" "$name"
    else
        mkdir -p "$source_dir/generated"
        cp "$name" "$source_dir/generated/$name"
    fi
done
printf 'Official rpcgen contract %s\n' "$(if [[ $check == true ]]; then printf verified; else printf regenerated; fi)"
