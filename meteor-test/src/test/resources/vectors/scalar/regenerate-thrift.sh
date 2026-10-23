#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$script_dir/../../../../../.." && pwd)"
compiler="${THRIFT_COMPILER:-thrift}"

# 合约与运行 SDK 使用官方 0.25.0；日常 Gradle 构建不运行此脚本。
# 官方源码 SHA256: 66da4707214c54c94bac082103dc67adaf9e08925662700f269170a7b534b214
if [[ "$("$compiler" --version)" != 'Thrift version 0.25.0' ]]; then
    printf '%s\n' 'This fixture requires Apache Thrift Compiler 0.25.0.' >&2
    exit 1
fi
"$compiler" --gen java:generated_annotations=suppress \
    -out "$repo_dir/meteor-test/src/test/java" "$script_dir/values.thrift"

# 只添加仓库版权头并清理行末空白，保留官方编译器的代码语义和排版。
python3 - "$repo_dir/meteor-test/src/test/java/net/hasor/example/contract/scalar/thrift/generated" <<'PY'
import pathlib
import re
import sys

directory = pathlib.Path(sys.argv[1])
license_header = '''/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
'''
for source in directory.glob('ThriftScalar*.java'):
    generated = re.sub(r'[ \t]+(?=\r?$)', '', source.read_text(), flags=re.MULTILINE)
    source.write_text(license_header + generated)
PY
