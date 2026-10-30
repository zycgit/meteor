#!/usr/bin/env bash
# Generate official Go bindings from the test-owned order and type Thrift files.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
test_dir=$(cd -- "$source_dir/../.." && pwd)
compiler=
while (($#)); do
    case "$1" in
        --compiler) compiler=$2; shift 2 ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done
[[ -n $compiler ]] || { printf '%s\n' '--compiler is required' >&2; exit 2; }
compiler=$(realpath -- "$compiler")
version=$("$compiler" --version)
[[ $version == 'Thrift version 0.25.0' ]] || { printf 'Expected Thrift 0.25.0, got %s\n' "$version" >&2; exit 1; }
mkdir -p "$source_dir/gen"
packages=(thriftorder typecontract)
contracts=("$test_dir/resource/protocol/thrift/order.thrift" "$test_dir/resource/protocol/thrift/types/types.thrift")
for index in "${!packages[@]}"; do
    "$compiler" --gen "go:package=${packages[index]},skip_remote" \
        -out "$source_dir/gen" "${contracts[index]}"
done
