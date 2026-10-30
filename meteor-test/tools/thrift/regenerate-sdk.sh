#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$script_dir/../../.." && pwd)
cd -- "$repo_dir"

# 在 native 工具链容器内执行；编译器、下载包和中间结果均归测试工程。
work="$repo_dir/meteor-test/build/sdk-compatibility/thrift-compiler-0.22.0"
mkdir -p "$work/downloads" "$work/bin" "$work/generated" "$work/tmp/home"
export HOME="$work/tmp/home" TMPDIR="$work/tmp"
archive="$work/downloads/thrift-0.22.0.tar.gz"

# 官方发布包携带已生成的 lexer/parser；固定校验值，不修改上游编译器源码。
if [[ ! -f "$archive" ]]; then
    curl --fail --location --silent --show-error \
        https://archive.apache.org/dist/thrift/0.22.0/thrift-0.22.0.tar.gz --output "$archive"
fi
printf '%s  %s\n' \
    794a0e455787960d9f27ab92c38e34da27e8deeda7a5db0e59dc64a00df8a1e5 "$archive" | sha256sum --check -
if [[ ! -d "$work/thrift-0.22.0" ]]; then
    tar -xzf "$archive" -C "$work"
fi
if [[ ! -x "$work/bin/thrift" ]]; then
    source_dir="$work/thrift-0.22.0/compiler/cpp/src"
    g++ -std=c++17 -O2 -I"$source_dir" \
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
[[ $("$work/bin/thrift" --version) == 'Thrift version 0.22.0' ]]

# SDK 订单契约使用较早生成器，保持 0.22 与当前 SDK 的共同 ABI；其他契约继续由各自版本生成。
"$work/bin/thrift" --gen java:generated_annotations=suppress \
    -out "$work/generated" meteor-test/resource/protocol/thrift/order.thrift
generated=net/hasor/peer/sdk/thrift/generated
destination=meteor-test/peer/java/apache-thrift/src/main/java/$generated
mkdir -p "$destination"
for name in OrderThriftService ThriftOrder ThriftOrderQuery; do
    # 保留原始输出，仅对签入文件统一 LF 并移除行尾空白。
    LC_ALL=C sed 's/\r$//;s/[[:blank:]]*$//' "$work/generated/$generated/$name.java" \
        > "$destination/$name.java"
done
"$work/bin/thrift" --version
