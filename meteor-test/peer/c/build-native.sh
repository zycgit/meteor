#!/usr/bin/env bash
# Compile rpcgen/libtirpc peers and capture their actual inputs and ELF libraries.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=
include_dir=${TIRPC_INCLUDE:-/usr/include/tirpc}
compiler=${CC:-gcc}
selected_peer=all
while (($#)); do
    case "$1" in
        --output) output_dir=$2; shift 2 ;;
        --include) include_dir=$2; shift 2 ;;
        --cc) compiler=$2; shift 2 ;;
        --peer) selected_peer=$2; shift 2 ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done
[[ -n $output_dir ]] || { printf '%s\n' '--output is required' >&2; exit 2; }
[[ -f $include_dir/rpc/rpc.h ]] || { printf 'Missing libtirpc headers: %s\n' "$include_dir" >&2; exit 2; }
case "$selected_peer" in all|interop|rpcbind) ;; *) printf 'Unknown peer: %s\n' "$selected_peer" >&2; exit 2 ;; esac
compiler=$(readlink -f -- "$(command -v -- "$compiler")")
include_dir=$(realpath -- "$include_dir")
mkdir -p -- "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
manifest_dir=$(mktemp -d "$output_dir/.manifest.XXXXXX")
trap 'rm -rf -- "$manifest_dir"' EXIT
touch "$manifest_dir/binaries.jsonl" "$manifest_dir/commands.jsonl" "$manifest_dir/logs.jsonl"

fingerprint() {
    local path digest size
    path=$(realpath -- "$1")
    digest=$(sha256sum -- "$path")
    size=$(stat -c '%s' -- "$path")
    jq -nc --arg path "$path" --arg hash "${digest%% *}" --argjson size "$size" \
        '{path:$path,sha256:$hash,size:$size}'
}

# Compilation and dynamic-loader results are the source of manifest identities.
peers=(interop rpcbind)
[[ $selected_peer == all ]] || peers=("$selected_peer")
for peer in "${peers[@]}"; do
    name=oncrpc_peer
    [[ $peer == interop ]] || name=oncrpc_rpcbind_peer
    sources=("$source_dir/$name.c")
    if [[ $peer == interop ]]; then
        sources+=("$source_dir/generated/interop_xdr.c" "$source_dir/generated/interop_svc.c" \
            "$source_dir/generated/interop_clnt.c")
    fi
    binary="$output_dir/$name"
    command=("$compiler" -std=c11 -O2 -Wall -Wextra -Wno-cast-function-type -Wno-unused-variable \
        -D_DEFAULT_SOURCE -I "$include_dir" -I "$source_dir/generated" "${sources[@]}" -ltirpc -pthread -o "$binary")
    printf -v command_text '%q ' "${command[@]}"
    log="$output_dir/$name-build.log"
    printf '$ %s\n' "$command_text" > "$log"
    if timeout 120 "${command[@]}" >> "$log" 2>&1; then
        printf '\nexit_code=0\n' >> "$log"
    else
        status=$?
        printf '\nexit_code=%s\n' "$status" >> "$log"
        printf 'Native compilation failed; see %s\n' "$log" >&2
        exit "$status"
    fi
    timeout 15 ldd "$binary" > "$manifest_dir/ldd.txt"
    awk '{for (field=1; field<=NF; field++) if ($field ~ /^\//) print $field}' "$manifest_dir/ldd.txt" \
        | while IFS= read -r library; do realpath -- "$library"; done \
        | sort -u > "$manifest_dir/libraries.txt"
    if grep -q 'not found' "$manifest_dir/ldd.txt" || ! grep -q '/libtirpc\.so' "$manifest_dir/libraries.txt"; then
        printf 'Cannot resolve libtirpc runtime dependencies for %s\n' "$binary" >&2
        exit 1
    fi
    while IFS= read -r library; do fingerprint "$library"; done \
        < "$manifest_dir/libraries.txt" > "$manifest_dir/libraries.jsonl"
    fingerprint "$binary" | jq --slurpfile libraries "$manifest_dir/libraries.jsonl" \
        '. + {libraries:$libraries}' >> "$manifest_dir/binaries.jsonl"
    jq -nc --arg command "$command_text" '$command' >> "$manifest_dir/commands.jsonl"
    fingerprint "$log" >> "$manifest_dir/logs.jsonl"
    printf '%s\n' "${sources[@]}" >> "$manifest_dir/source-paths.txt"
done

# Record source, header and toolchain provenance once, after all peers compile.
find "$source_dir/generated" "$include_dir" -type f -name '*.h' >> "$manifest_dir/source-paths.txt"
find "$source_dir" -maxdepth 1 -type f -name '*.h' >> "$manifest_dir/source-paths.txt"
printf '%s\n' "$source_dir/../../resource/protocol/x/interop.x" "${BASH_SOURCE[0]}" \
    >> "$manifest_dir/source-paths.txt"
while IFS= read -r path; do fingerprint "$path"; done \
    < <(sort -u "$manifest_dir/source-paths.txt") > "$manifest_dir/inputs.jsonl"
for executable in "$compiler" rpcgen; do
    identity=$(timeout 15 "$executable" --version 2>&1)
    jq -nc --arg executable "$executable" --arg output "$identity" \
        '{command:[$executable,"--version"],output:$output}' >> "$manifest_dir/toolchain.jsonl"
done
jq -n --slurpfile binaries "$manifest_dir/binaries.jsonl" --slurpfile inputs "$manifest_dir/inputs.jsonl" \
    --slurpfile commands "$manifest_dir/commands.jsonl" --slurpfile logs "$manifest_dir/logs.jsonl" \
    --slurpfile toolchain "$manifest_dir/toolchain.jsonl" \
    '{kind:"native-oncrpc-peer",status:"passed",binaries:$binaries,source_inputs:$inputs,
      build_commands:$commands,build_logs:$logs,toolchain:$toolchain}' > "$output_dir/native-peer-runtime.json"
printf '%s\n' "$output_dir/native-peer-runtime.json"
