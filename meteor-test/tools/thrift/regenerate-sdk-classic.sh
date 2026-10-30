#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$script_dir/../../.." && pwd)
cd -- "$repo_dir"

# 在 native 工具链容器内执行；经典编译器及其原始输出留在测试工程。
work="$repo_dir/meteor-test/build/sdk-compatibility/thrift-compiler-0.10.0"
mkdir -p "$work/downloads" "$work/bin" "$work/generated" "$work/tmp/home"
export HOME="$work/tmp/home" TMPDIR="$work/tmp"
archive="$work/downloads/thrift-0.10.0.tar.gz"

# Apache 归档附带 SHA1；另固定 SHA256，保留下载与发布校验记录。
if [[ ! -f "$archive" ]]; then
    curl --fail --location --silent --show-error \
        https://archive.apache.org/dist/thrift/0.10.0/thrift-0.10.0.tar.gz --output "$archive"
fi
curl --fail --location --silent --show-error \
    https://archive.apache.org/dist/thrift/0.10.0/thrift-0.10.0.tar.gz.sha --output "$archive.sha"
printf '%s  %s\n' \
    2289d02de6e8db04cbbabb921aeb62bfe3098c4c83f36eec6c31194301efa10b "$archive" | sha256sum --check -
published_sha1=$(awk '{ print $1 }' "$archive.sha")
printf '%s  %s\n' "$published_sha1" "$archive" | sha1sum --check -
if [[ ! -d "$work/thrift-0.10.0" ]]; then
    tar -xzf "$archive" -C "$work"
fi
if [[ ! -x "$work/bin/thrift" ]]; then
    source_dir="$work/thrift-0.10.0/compiler/cpp/src"
    g++ -std=c++11 -O2 -I"$source_dir" \
        "$source_dir/thrift/main.cc" \
        "$source_dir/thrift/common.cc" \
        "$source_dir/thrift/audit/t_audit.cpp" \
        "$source_dir/thrift/generate/t_generator.cc" \
        "$source_dir/thrift/generate/t_java_generator.cc" \
        "$source_dir/thrift/parse/parse.cc" \
        "$source_dir/thrift/parse/t_typedef.cc" \
        "$source_dir/thrift/thriftl.cc" \
        "$source_dir/thrift/thrifty.cc" \
        -o "$work/bin/thrift"
fi
[[ $("$work/bin/thrift" --version) == 'Thrift version 0.10.0' ]]

# 同一订单 IDL 生成旧 SDK 私有契约，保留原始输出，只规范签入文件的行尾。
"$work/bin/thrift" --gen java:generated_annotations=suppress \
    -out "$work/generated" meteor-test/resource/protocol/thrift/order.thrift
generated=net/hasor/peer/sdk/thrift/generated
destination=meteor-test/peer/java/apache-thrift/0.10.0/src/main/java/$generated
mkdir -p "$destination"
for name in OrderThriftService ThriftOrder ThriftOrderQuery; do
    LC_ALL=C sed 's/\r$//;s/[[:blank:]]*$//' "$work/generated/$generated/$name.java" \
        > "$destination/$name.java"
done
"$work/bin/thrift" --version
