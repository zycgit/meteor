#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$script_dir/../../.." && pwd)"
compiler="${THRIFT_COMPILER:-thrift}"

# 已签入的合约与运行 SDK 都使用官方 0.25.0；日常 Gradle 构建不运行此脚本。
if [[ "$("$compiler" --version)" != 'Thrift version 0.25.0' ]]; then
    printf '%s\n' 'This fixture requires Apache Thrift Compiler 0.25.0.' >&2
    exit 1
fi
"$compiler" --gen java:generated_annotations=suppress \
    -out "$repo_dir/meteor-test/peer/java/meteor/src/main/java" \
    "$repo_dir/meteor-test/resource/protocol/thrift/complex/values.thrift"

# 只添加仓库版权头并清理行尾空白，其余内容保留官方编译器输出。
output_dir="$repo_dir/meteor-test/peer/java/meteor/src/main/java/net/hasor/example/contract/complex/thrift/generated"
for source in "$output_dir"/ThriftComplex*.java; do
    temporary="$(mktemp "$source.XXXXXX")"
    {
        cat <<'LICENSE'
/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
LICENSE
        sed 's/[[:blank:]]*$//' "$source"
    } > "$temporary"
    mv "$temporary" "$source"
done
