#!/usr/bin/env bash
# Developer-only regeneration with the locked tools from the existing Node peer image.
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
host_dir=$(cd -- "$script_dir/../../../.." && pwd)
order_dir="$host_dir/../met-example/src/main/resources/proto"

# Select the already-built image for the Docker daemon architecture; do not install tools on the host.
command -v docker >/dev/null || { printf 'Docker is required.\n' >&2; exit 2; }
image=${METEOR_TEST_IMAGE_NODE:-}
if [[ -z "$image" ]]; then
    architecture=$(docker info --format '{{.Architecture}}')
    case "$architecture" in
        amd64|x86_64) architecture=amd64 ;;
        arm64|aarch64) architecture=arm64 ;;
        *) printf 'Unsupported Docker architecture: %s\n' "$architecture" >&2; exit 2 ;;
    esac
    image="meteor-test/node:local-$architecture"
fi
if ! docker image inspect "$image" >/dev/null 2>&1; then
    printf 'Build the Node peer image first with meteor-test/docker/run.sh; missing image: %s\n' "$image" >&2
    exit 2
fi

# Copy contracts into an owned container, keeping Docker paths independent of the host OS.
container="meteor-connect-regenerate-$$-$RANDOM"
cleanup() {
    docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker create --name "$container" --network none \
    --workdir /opt/meteor-peer/node/connect --entrypoint /bin/sh "$image" -c '
    set -eu
    mkdir -p /tmp/meteor-generated
    node_modules/.bin/buf generate /tmp/meteor-connect-contracts \
        --template /tmp/meteor-buf.gen.yaml --output /tmp/meteor-generated
    node_modules/.bin/buf generate /tmp/meteor-order-contracts \
        --path /tmp/meteor-order-contracts/order.proto \
        --template /tmp/meteor-buf.gen.yaml --output /tmp/meteor-generated
' >/dev/null
docker cp "$host_dir/src/test/resources/connect/proto" "$container:/tmp/meteor-connect-contracts"
docker cp "$order_dir" "$container:/tmp/meteor-order-contracts"
docker cp "$script_dir/buf.gen.yaml" "$container:/tmp/meteor-buf.gen.yaml"

# Replace source output only after both contracts generated successfully.
docker start -a "$container"
status=$(docker inspect --format '{{.State.ExitCode}}' "$container")
if [[ "$status" -ne 0 ]]; then
    printf 'Connect contract generation failed with exit code %s.\n' "$status" >&2
    exit "$status"
fi
mkdir -p "$script_dir/generated"
docker cp "$container:/tmp/meteor-generated/generated/." "$script_dir/generated"
