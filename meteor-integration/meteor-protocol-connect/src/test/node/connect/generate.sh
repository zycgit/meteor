#!/usr/bin/env bash
# Regenerate only the test peer using the pinned official Buf and Protobuf-ES tools.
set -euo pipefail
cd "$(dirname "$0")"
node_modules/.bin/buf generate ../../resources/proto --template buf.gen.yaml
