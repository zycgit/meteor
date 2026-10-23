#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$script_dir/../../../.." && pwd)"
compiler_dir="${1:-$repo_dir/build/thrift-compiler-0.25.0}"
archive="$compiler_dir/downloads/thrift-0.25.0.tar.gz"
mkdir -p "$compiler_dir/downloads" "$compiler_dir/bin"

# 官方 release tar 自带 lexer/parser 的生成结果，只需本机 C++17 编译器。
if [[ ! -f "$archive" ]]; then
    curl --fail --location --silent --show-error \
        https://archive.apache.org/dist/thrift/0.25.0/thrift-0.25.0.tar.gz \
        --output "$archive"
fi
printf '%s  %s\n' \
    66da4707214c54c94bac082103dc67adaf9e08925662700f269170a7b534b214 "$archive" \
    | sha256sum --check -
if [[ ! -d "$compiler_dir/thrift-0.25.0" ]]; then
    tar -xzf "$archive" -C "$compiler_dir"
fi

# 源文件取自官方 compiler/cpp/Makefile.am，仅选择本契约需要的 Java generator。
source_dir="$compiler_dir/thrift-0.25.0/compiler/cpp/src"
cxx="${CXX:-g++}"
"$cxx" -std=c++17 -O2 -I"$source_dir" \
    "$source_dir/thrift/main.cc" \
    "$source_dir/thrift/common.cc" \
    "$source_dir/thrift/audit/t_audit.cpp" \
    "$source_dir/thrift/generate/t_generator.cc" \
    "$source_dir/thrift/generate/t_java_generator.cc" \
    "$source_dir/thrift/parse/parse.cc" \
    "$source_dir/thrift/parse/t_typedef.cc" \
    "$source_dir/thrift/thriftl.cc" \
    "$source_dir/thrift/thrifty.cc" \
    -o "$compiler_dir/bin/thrift"
"$compiler_dir/bin/thrift" --version
