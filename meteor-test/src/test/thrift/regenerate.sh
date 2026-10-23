#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
compiler="${THRIFT_COMPILER:-thrift}"
version="$("$compiler" --version)"
case "$version" in
    'Thrift version 0.25.0') ;;
    *)
        printf 'Expected Apache Thrift 0.25.0 compiler, received: %s\n' "$version" >&2
        exit 1
        ;;
esac

# 供应商生成代码不手工格式化；日常 Gradle 构建直接编译已签入的测试文件。
mkdir -p "$script_dir/../java"
"$compiler" --gen java:generated_annotations=suppress \
    -out "$script_dir/../java" "$script_dir/interop.thrift"
