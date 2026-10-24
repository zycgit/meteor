#!/usr/bin/env bash
set -uo pipefail

# All artifacts and shared control files stay inside the current run's volume.
cd /workspace/meteor
mkdir -p meteor-test/build/container-tmp "$METEOR_TEST_EVIDENCE_DIR"
export TMPDIR=/workspace/meteor/meteor-test/build/container-tmp

# Record only test-owned configuration, never the complete inherited environment.
{
    printf 'run=%s\n' "$METEOR_TEST_RUN_ID"
    printf 'network=%s\n' "$METEOR_TEST_NETWORK"
    uname -a
    java -version 2>&1
    docker version
} > "$METEOR_TEST_EVIDENCE_DIR/runner-environment.txt" 2>&1

# Existing development snapshots are supplied as repository data, without Maven settings.
for coordinate in 'cobble-all:lib.cobble' 'neta-core:lib.neta' 'neta-codec-http:lib.neta'; do
    artifact=${coordinate%%:*}
    property=${coordinate#*:}
    version=$(sed -n "s/^${property}=//p" gradle.properties | tr -d '\r')
    repository="/root/.m2/repository/net/hasor/$artifact/$version"
    if [[ "$version" == *-SNAPSHOT && ! -d "$repository" ]]; then
        printf 'Local snapshot %s:%s is absent. Supply repository data with --maven-seed; Gradle may reuse its existing cache.\n' \
            "$artifact" "$version" >&2
    fi
done

# Every argument remains a separate token, including --tests patterns and -D values.
if [[ $# -eq 0 ]]; then
    set -- :meteor-test:test --no-daemon --console=plain --rerun-tasks
fi
printf '%s\n' "$@" > "$METEOR_TEST_EVIDENCE_DIR/gradle-arguments.txt"
exec bash ./gradlew "$@"
