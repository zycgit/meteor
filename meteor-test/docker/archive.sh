#!/usr/bin/env bash
set -euo pipefail

# Preserve reports from every invoked Gradle project without copying compiled output.
cd /workspace/meteor
output="/workspace/meteor-export/$METEOR_TEST_RUN_ID"
mkdir -p "$output"
while IFS= read -r -d '' directory; do
    relative=${directory#./}
    destination="$output/${relative%/build}"
    mkdir -p "$destination/build"
    for name in reports test-results interoperability docker-evidence container-scenarios container-tmp peer-runtime go-peer/runs; do
        if [[ -d "$directory/$name" ]]; then
            mkdir -p "$destination/build/$(dirname "$name")"
            cp -a "$directory/$name" "$destination/build/$name"
        fi
    done

    # Peer diagnostics are separate from JUnit XML and remain useful after startup failures.
    while IFS= read -r -d '' diagnostic; do
        cp -a "$diagnostic" "$destination/build/"
    done < <(find "$directory" -mindepth 1 -maxdepth 1 \
        \( -name '*-logs' -o -name '*.json' -o -name '*.jsonl' -o -name '*.log' -o -name '*.txt' \) -print0)
done < <(find . -type d \( -name .git -o -name .gradle -o -name node_modules \) -prune \
    -o -type d -name build -print0 -prune)
