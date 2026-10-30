#!/usr/bin/env bash
# Regenerate the grpc-web 1.5 clients with their matching official JavaScript generator.
set -euo pipefail

# All tools execute in the existing native image; downloads and temporary output stay in meteor-test/build.
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$script_dir/../../../../.." && pwd)
[[ -f "$repo_dir/meteor-test/resource/protocol/proto/grpc/web.proto" ]]
architecture=$(docker info --format '{{.Architecture}}')
case "$architecture" in
    amd64|x86_64) architecture=amd64 ;;
    arm64|aarch64) architecture=arm64 ;;
    *) printf 'Unsupported Docker architecture: %s\n' "$architecture" >&2; exit 2 ;;
esac
image=${METEOR_TEST_IMAGE_NATIVE:-meteor-test/native:persistent-$architecture}
docker image inspect "$image" >/dev/null
docker run --rm -i --user "$(id -u):$(id -g)" --entrypoint bash \
    --mount "type=bind,src=$repo_dir,dst=/workspace/meteor" --workdir /workspace/meteor \
    "$image" -s -- "$architecture" <<'CONTAINER'
set -euo pipefail

# Release artifacts are immutable inputs; each architecture uses an explicit SHA-256.
case "$1" in
    amd64)
        js_arch=x86_64
        web_arch=x86_64
        js_sha=c57ba4130471c642462fcf98c844a3c933f6c4708b9fddc859900fd2a2e72a45
        web_sha=2e6e074497b221045a14d5a54e9fc910945bfdd1198b12b9fc23686a95671d64
        ;;
    arm64)
        js_arch=aarch_64
        web_arch=aarch64
        js_sha=86194b1c6baee994bb06d162887b9edace6b32a8ed971eac07fdf5d2470c6937
        web_sha=522e958568cdeabdd20ef3c97394fc067ff8e374a728c08b7410bf5de8f57783
        ;;
esac
cache=/workspace/meteor/meteor-test/build/sdk-compatibility/grpcweb-legacy-codegen
mkdir -p "$cache"
js_archive="protobuf-javascript-3.21.4-linux-$js_arch.tar.gz"
web_binary="protoc-gen-grpc-web-1.5.0-linux-$web_arch"
if [[ ! -f "$cache/$js_archive" ]]; then
    curl -fL "https://github.com/protocolbuffers/protobuf-javascript/releases/download/v3.21.4/$js_archive" \
        -o "$cache/$js_archive"
fi
if [[ ! -f "$cache/$web_binary" ]]; then
    curl -fL "https://github.com/grpc/grpc-web/releases/download/1.5.0/$web_binary" -o "$cache/$web_binary"
fi
printf '%s  %s\n' "$js_sha" "$cache/$js_archive" "$web_sha" "$cache/$web_binary" | sha256sum --check
[[ $(protoc --version) == 'libprotoc 3.21.12' ]]

# Generate both modes before replacing checked-in output, using the same source contract.
tools="$cache/tools-$1"
output="$cache/generated-$1"
mkdir -p "$tools" "$output/binary" "$output/text"
tar -xzf "$cache/$js_archive" -C "$tools"
chmod +x "$cache/$web_binary"
contract=/workspace/meteor/meteor-test/resource/protocol/proto/grpc
for mode in binary text; do
    wire=grpcweb
    if [[ "$mode" == text ]]; then
        wire=grpcwebtext
    fi
    protoc -I "$contract" --plugin="protoc-gen-js=$tools/bin/protoc-gen-js" \
        --plugin="protoc-gen-grpc-web=$cache/$web_binary" \
        --js_out="import_style=commonjs,binary:$output/$mode" \
        --grpc-web_out="import_style=commonjs,mode=$wire:$output/$mode" web.proto
done
target=meteor-test/peer/node/grpcweb/versions/generated
mkdir -p "$target/binary" "$target/text"
cp "$output/binary/"web*.js "$target/binary/"
cp "$output/text/"web*.js "$target/text/"
sha256sum "$tools/bin/protoc-gen-js" "$cache/$web_binary" "$contract/web.proto" "$target/"*/web*.js
CONTAINER
