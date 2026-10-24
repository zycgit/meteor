#!/usr/bin/env bash
set -euo pipefail

# Resolve paths before changing directory; this script also works from outside the checkout.
script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository=$(cd -- "$script_directory/../.." && pwd)
no_build=false
keep_workspace=false
prepare_only=false
platform=${METEOR_TEST_PLATFORM:-}
maven_seed=${HOME:-}/.m2/repository/net/hasor
seed_explicit=false
gradle_arguments=(:meteor-test:test --no-daemon --console=plain --rerun-tasks)
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-build) no_build=true; shift ;;
        --keep-workspace) keep_workspace=true; shift ;;
        --prepare-only) prepare_only=true; shift ;;
        --platform|--maven-seed)
            [[ $# -ge 2 ]] || { printf 'Missing value for %s\n' "$1" >&2; exit 2; }
            if [[ "$1" == --platform ]]; then platform=$2; else maven_seed=$2; seed_explicit=true; fi
            shift 2 ;;
        --help|-h)
            printf '%s\n' 'Usage: run.sh [--no-build] [--keep-workspace] [--prepare-only]' \
                '              [--platform linux/amd64|linux/arm64] [--maven-seed PATH] [-- GRADLE_ARGS...]' \
                'Default: :meteor-test:test --no-daemon --console=plain --rerun-tasks'
            exit 0 ;;
        --)
            shift
            if [[ $# -gt 0 ]]; then gradle_arguments=("$@"); fi
            break ;;
        *) gradle_arguments=("$@"); break ;;
    esac
done
# The only host toolchain is Docker with its Compose plugin and Linux container support.
command -v docker >/dev/null || { printf 'Docker is required.\n' >&2; exit 2; }
docker compose version >/dev/null
daemon_os=$(docker info --format '{{.OSType}}')
[[ "$daemon_os" == linux ]] || { printf 'Switch Docker to Linux containers.\n' >&2; exit 2; }
if [[ -z "$platform" ]]; then
    architecture=$(docker info --format '{{.Architecture}}')
    case "$architecture" in
        amd64|x86_64) platform=linux/amd64 ;;
        arm64|aarch64) platform=linux/arm64 ;;
        *) printf 'Unsupported Docker architecture: %s\n' "$architecture" >&2; exit 2 ;;
    esac
fi
case "$platform" in linux/amd64|linux/arm64) ;; *) printf 'Unsupported platform: %s\n' "$platform" >&2; exit 2 ;; esac
architecture=${platform#linux/}
cache_key=${METEOR_TEST_CACHE_KEY:-$architecture}
case "$cache_key" in
    [!a-zA-Z0-9]*|*[!a-zA-Z0-9_.-]*)
        printf 'Invalid METEOR_TEST_CACHE_KEY: use letters, digits, dots, underscores or hyphens, starting with a letter or digit.\n' >&2
        exit 2 ;;
esac
run_id="$(date -u +%Y%m%dt%H%M%Sz)-$(printf '%04x%04x' "$RANDOM" "$RANDOM")"
export METEOR_TEST_RUN_ID=$run_id
export METEOR_TEST_PLATFORM=$platform
export METEOR_TEST_CACHE_KEY=$cache_key
export METEOR_TEST_PROJECT="meteor-test-$run_id"
export METEOR_TEST_NETWORK="$METEOR_TEST_PROJECT-network"
export METEOR_TEST_WORKSPACE_VOLUME="$METEOR_TEST_PROJECT-workspace"
export METEOR_TEST_GRADLE_VOLUME="meteor-test-gradle-$cache_key"
export METEOR_TEST_MAVEN_VOLUME="meteor-test-maven-$cache_key"
export METEOR_TEST_IMAGE_RUNNER=${METEOR_TEST_IMAGE_RUNNER:-meteor-test/runner:local-$architecture}
export METEOR_TEST_IMAGE_JAVA=${METEOR_TEST_IMAGE_JAVA:-meteor-test/java:local-$architecture}
export METEOR_TEST_IMAGE_PYTHON=${METEOR_TEST_IMAGE_PYTHON:-meteor-test/python:local-$architecture}
export METEOR_TEST_IMAGE_NODE=${METEOR_TEST_IMAGE_NODE:-meteor-test/node:local-$architecture}
export METEOR_TEST_IMAGE_NATIVE=${METEOR_TEST_IMAGE_NATIVE:-meteor-test/native:local-$architecture}
export METEOR_TEST_IMAGE_GO=${METEOR_TEST_IMAGE_GO:-meteor-test/go:local-$architecture}
export METEOR_TEST_IMAGE_BROWSER=${METEOR_TEST_IMAGE_BROWSER:-meteor-test/browser:local-$architecture}
export METEOR_TEST_IMAGE_ENVOY=${METEOR_TEST_IMAGE_ENVOY:-meteor-test/envoy:local-$architecture}
export METEOR_TEST_IMAGE_H2=${METEOR_TEST_IMAGE_H2:-meteor-test/h2:local-$architecture}
export METEOR_TEST_IMAGE_NACOS=${METEOR_TEST_IMAGE_NACOS:-nacos/nacos-server:v3.1.2}
export METEOR_TEST_IMAGE_ZOOKEEPER=${METEOR_TEST_IMAGE_ZOOKEEPER:-zookeeper:3.9.6}
output="$repository/build/docker-runs/$run_id"
mkdir -p "$output"
compose=(docker compose --project-directory "$repository" -f "$script_directory/compose.yaml")
runner_name="$METEOR_TEST_PROJECT-runner"
archive_name="$METEOR_TEST_PROJECT-archive"
seed_name="$METEOR_TEST_PROJECT-seed"
retain_resources=false

# Always retain failure evidence, then release only resources owned by this run.
finish() {
    status=$?
    trap - EXIT INT TERM
    set +e
    printf '%s\n' "$status" > "$output/exit-code.txt"
    docker logs "$runner_name" > "$output/runner.log" 2>&1
    "${compose[@]}" config > "$output/compose.yaml" 2> "$output/compose-config-error.log"
    docker ps -a --filter "label=net.hasor.meteor.test.run=$run_id" \
        --format '{{json .}}' > "$output/containers.jsonl"
    if docker volume inspect "$METEOR_TEST_WORKSPACE_VOLUME" >/dev/null 2>&1; then
        archived=false
        docker create --name "$archive_name" --label "net.hasor.meteor.test.run=$run_id" \
            --volume "$METEOR_TEST_WORKSPACE_VOLUME:/workspace" --env "METEOR_TEST_RUN_ID=$run_id" \
            "$METEOR_TEST_IMAGE_RUNNER" /bin/bash /workspace/meteor/meteor-test/docker/archive.sh \
            > "$output/archive-container.txt" 2> "$output/archive-error.log"
        if [[ $? -eq 0 ]]; then
            docker start -a "$archive_name" >> "$output/archive-error.log" 2>&1
            if [[ $? -eq 0 ]]; then
                docker cp "$archive_name:/workspace/meteor-export/$run_id/." "$output/" \
                    >> "$output/archive-error.log" 2>&1
                if [[ $? -eq 0 ]]; then archived=true; fi
            fi
        fi
        docker rm -f "$archive_name" >/dev/null 2>&1
        if [[ "$archived" == false ]]; then
            printf 'Report archival failed; workspace retained for inspection.\n' >&2
            keep_workspace=true
            if [[ "$status" -eq 0 ]]; then status=1; fi
        fi
    fi
    if [[ "$retain_resources" == false ]]; then
        cleanup_failed=false
        while IFS= read -r container; do
            if [[ -n "$container" ]]; then
                docker rm -f "$container" >> "$output/cleanup.log" 2>&1 || cleanup_failed=true
            fi
        done < <(docker ps -aq --filter "label=net.hasor.meteor.test.run=$run_id")
        "${compose[@]}" down --remove-orphans >> "$output/cleanup.log" 2>&1 || cleanup_failed=true
        if [[ "$keep_workspace" == false ]]; then
            docker volume rm "$METEOR_TEST_WORKSPACE_VOLUME" >> "$output/cleanup.log" 2>&1 || cleanup_failed=true
        fi
        if [[ "$cleanup_failed" == true && "$status" -eq 0 ]]; then status=1; fi
    fi
    printf '%s\n' "$status" > "$output/exit-code.txt"
    printf 'Docker test artifacts: %s\n' "$output"
    if [[ "$retain_resources" == true ]]; then
        printf 'Prepared resources retained. Configuration: %s/compose.env\n' "$output"
    elif [[ "$keep_workspace" == true ]]; then
        printf 'Workspace retained: %s\n' "$METEOR_TEST_WORKSPACE_VOLUME"
    fi
    exit "$status"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Record reproducible configuration before any potentially failing image build.
for name in METEOR_TEST_PROJECT METEOR_TEST_RUN_ID METEOR_TEST_PLATFORM METEOR_TEST_CACHE_KEY METEOR_TEST_NETWORK \
    METEOR_TEST_WORKSPACE_VOLUME METEOR_TEST_GRADLE_VOLUME METEOR_TEST_MAVEN_VOLUME \
    METEOR_TEST_IMAGE_RUNNER METEOR_TEST_IMAGE_JAVA METEOR_TEST_IMAGE_PYTHON METEOR_TEST_IMAGE_NODE \
    METEOR_TEST_IMAGE_NATIVE METEOR_TEST_IMAGE_GO METEOR_TEST_IMAGE_BROWSER METEOR_TEST_IMAGE_ENVOY \
    METEOR_TEST_IMAGE_H2 METEOR_TEST_IMAGE_NACOS METEOR_TEST_IMAGE_ZOOKEEPER; do
    printf '%s=%s\n' "$name" "${!name}" >> "$output/compose.env"
done
printf '%s\n' "${gradle_arguments[@]}" > "$output/gradle-arguments.txt"
docker version > "$output/docker-version.txt"
docker compose version > "$output/compose-version.txt"

# SDKs and compilers are built into architecture-specific images; nothing is installed on the host.
if [[ "$no_build" == false ]]; then
    for runtime in java python node native go browser h2 envoy runner; do
        case "$runtime" in
            java) image=$METEOR_TEST_IMAGE_JAVA ;;
            python) image=$METEOR_TEST_IMAGE_PYTHON ;;
            node) image=$METEOR_TEST_IMAGE_NODE ;;
            native) image=$METEOR_TEST_IMAGE_NATIVE ;;
            go) image=$METEOR_TEST_IMAGE_GO ;;
            browser) image=$METEOR_TEST_IMAGE_BROWSER ;;
            h2) image=$METEOR_TEST_IMAGE_H2 ;;
            envoy) image=$METEOR_TEST_IMAGE_ENVOY ;;
            runner) image=$METEOR_TEST_IMAGE_RUNNER ;;
        esac
        docker build --platform "$platform" --target "$runtime" --tag "$image" \
            --file "$script_directory/images/Dockerfile" "$repository" \
            2>&1 | tee "$output/build-$runtime.log"
    done
    docker pull --platform "$platform" "$METEOR_TEST_IMAGE_NACOS" 2>&1 | tee "$output/pull-nacos.log"
    docker pull --platform "$platform" "$METEOR_TEST_IMAGE_ZOOKEEPER" 2>&1 | tee "$output/pull-zookeeper.log"
fi
for image in "$METEOR_TEST_IMAGE_RUNNER" "$METEOR_TEST_IMAGE_JAVA" "$METEOR_TEST_IMAGE_PYTHON" \
    "$METEOR_TEST_IMAGE_NODE" "$METEOR_TEST_IMAGE_NATIVE" "$METEOR_TEST_IMAGE_GO" \
    "$METEOR_TEST_IMAGE_BROWSER" "$METEOR_TEST_IMAGE_ENVOY" "$METEOR_TEST_IMAGE_H2" \
    "$METEOR_TEST_IMAGE_NACOS" "$METEOR_TEST_IMAGE_ZOOKEEPER"; do
    docker image inspect --format '{{json .}}' "$image" >> "$output/images.jsonl"
    image_platform=$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image")
    if [[ "$image_platform" != "$platform" ]]; then
        printf 'Image %s uses %s; this run requires %s.\n' "$image" "$image_platform" "$platform" >&2
        exit 2
    fi
done

docker volume create "$METEOR_TEST_GRADLE_VOLUME" > /dev/null
docker volume create "$METEOR_TEST_MAVEN_VOLUME" > /dev/null
if [[ "$seed_explicit" == true && ! -d "$maven_seed" ]]; then
    printf 'Maven seed directory does not exist: %s\n' "$maven_seed" >&2
    exit 2
fi
seed_has_data=false
if [[ -d "$maven_seed" ]]; then
    for entry in "$maven_seed"/* "$maven_seed"/.[!.]* "$maven_seed"/..?*; do
        if [[ -e "$entry" || -L "$entry" ]]; then seed_has_data=true; break; fi
    done
fi
if [[ "$seed_has_data" == true ]]; then
    docker create --name "$seed_name" --label "net.hasor.meteor.test.run=$run_id" \
        --volume "$METEOR_TEST_MAVEN_VOLUME:/root/.m2" "$METEOR_TEST_IMAGE_RUNNER" \
        /bin/sh -c 'mkdir -p /root/.m2/repository/net/hasor' > "$output/seed-container.txt"
    docker start -a "$seed_name"
    docker cp "$maven_seed/." "$seed_name:/root/.m2/repository/net/hasor"
    docker rm "$seed_name" > /dev/null
fi

# The fresh workspace volume receives the source tree from the runner image.
if [[ "$prepare_only" == true ]]; then
    "${compose[@]}" create --no-build runner
    retain_resources=true
    printf 'Prepared only; no Gradle task was executed.\n'
    printf 'Prepared container uses the default Compose command; pass Gradle arguments to compose run when executing.\n'
else
    "${compose[@]}" run --no-deps --use-aliases --name "$runner_name" runner "${gradle_arguments[@]}"
fi
