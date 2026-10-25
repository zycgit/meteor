#!/usr/bin/env bash
# Regenerate official Go bindings using protoc and pinned Go plugins.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
test_dir=$(cd -- "$source_dir/../.." && pwd)
build_dir="$test_dir/build"
go_command=go
protoc_command=protoc
while (($#)); do
    case "$1" in
        --build-dir) build_dir=$2; shift 2 ;;
        --go) go_command=$2; shift 2 ;;
        --protoc) protoc_command=$2; shift 2 ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done
go_command=$(command -v -- "$go_command")
protoc_command=$(command -v -- "$protoc_command")
mkdir -p "$build_dir"
build_dir=$(cd -- "$build_dir" && pwd)
export GOTOOLCHAIN=local GOWORK=off CGO_ENABLED=0
export GOBIN="$build_dir/go-generators" GOPATH="$build_dir/go-path"
export GOMODCACHE="$build_dir/go-mod-cache" GOCACHE="$build_dir/go-build-cache"
cd -- "$source_dir"
for package in google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.12 \
    google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.6.2 \
    connectrpc.com/connect/cmd/protoc-gen-connect-go@v1.19.1; do
    "$go_command" install "$package"
done

# Both contracts use the same pinned generators and committed module graph.
directories=("$test_dir/src/test/resources/order" "$test_dir/src/test/resources/vectors/types")
filenames=(order.proto types.proto)
packages=(orderpb typepb)
for index in "${!filenames[@]}"; do
    filename=${filenames[index]}
    package=${packages[index]}
    output="$source_dir/gen/$package"
    mkdir -p "$output"
    command=("$protoc_command" -I "${directories[index]}")
    for plugin in go go-grpc connect-go; do
        command+=("--plugin=protoc-gen-$plugin=$GOBIN/protoc-gen-$plugin" \
            "--${plugin}_out=$output" "--${plugin}_opt=paths=source_relative" \
            "--${plugin}_opt=M$filename=meteor.test/orderpeer/gen/$package;$package")
    done
    "${command[@]}" "$filename"
done
