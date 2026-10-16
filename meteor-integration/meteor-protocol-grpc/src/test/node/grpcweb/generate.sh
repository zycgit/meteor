#!/usr/bin/env bash
# Regenerate test-only clients from src/test/resources/proto/web.proto.
# Official tools: protoc 36.2, protoc-gen-js 4.0.3, protoc-gen-grpc-web 2.1.1.
# https://github.com/protocolbuffers/protobuf/releases/tag/v36.2
# https://github.com/protocolbuffers/protobuf-javascript/releases/tag/v4.0.3
# https://github.com/grpc/grpc-web/releases/tag/2.1.1
# Generated files retain their official Apache-2.0/BSD license headers.
# Tools must be on PATH; test execution uses checked-in generated files.
set -euo pipefail
cd "$(dirname "$0")"
protoc -I ../../resources/proto --js_out=import_style=commonjs,binary:generated/binary \
  --grpc-web_out=import_style=commonjs,mode=grpcweb:generated/binary web.proto
protoc -I ../../resources/proto --js_out=import_style=commonjs,binary:generated/text \
  --grpc-web_out=import_style=commonjs,mode=grpcwebtext:generated/text web.proto
