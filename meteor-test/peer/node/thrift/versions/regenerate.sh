#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$script_dir/../../../../.." && pwd)
architecture=$(docker info --format '{{.Architecture}}')
case "$architecture" in
    x86_64) architecture=amd64 ;;
    aarch64) architecture=arm64 ;;
esac
image=${METEOR_NATIVE_IMAGE:-meteor-test/native:persistent-$architecture}

# 宿主只调用 Docker，官方编译器源码、下载包与中间产物全部归 meteor-test/build。
docker run --rm --interactive --user "$(id -u):$(id -g)" \
    --mount "type=bind,source=$repo_dir,target=/workspace/meteor" \
    --workdir /workspace/meteor "$image" bash -se <<'CONTAINER'
set -euo pipefail
work=/workspace/meteor/meteor-test/build/sdk-compatibility/node-thrift-generator/0.25.0
bindings=meteor-test/peer/node/thrift/versions
mkdir -p "$work/tmp/home" "$bindings/generated" "$bindings/generated-bigint"
export HOME="$work/tmp/home" TMPDIR="$work/tmp"

# 基础绑定固定使用现有官方 0.17 编译器，跨版本回调 API 无需修改生成代码。
[[ $(thrift --version) == 'Thrift version 0.17.0' ]]
thrift --gen js:node -out "$bindings/generated" "$bindings/version-echo.thrift"

# BigInt 绑定使用官方 0.25 生成器；源码包自带 lexer/parser，无需额外生成工具。
archive="$work/thrift.tar.gz"
if [[ ! -f "$archive" ]]; then
    curl --fail --location --silent --show-error \
        https://archive.apache.org/dist/thrift/0.25.0/thrift-0.25.0.tar.gz --output "$archive"
fi
printf '%s  %s\n' \
    66da4707214c54c94bac082103dc67adaf9e08925662700f269170a7b534b214 "$archive" | sha256sum --check -
if [[ ! -d "$work/thrift-0.25.0" ]]; then
    tar -xzf "$archive" -C "$work"
fi
if [[ ! -x "$work/thrift-js" ]]; then
    source_dir="$work/thrift-0.25.0/compiler/cpp/src"
    g++ -std=c++17 -O2 -I"$source_dir" \
        "$source_dir/thrift/main.cc" \
        "$source_dir/thrift/common.cc" \
        "$source_dir/thrift/audit/t_audit.cpp" \
        "$source_dir/thrift/generate/t_generator.cc" \
        "$source_dir/thrift/generate/t_js_generator.cc" \
        "$source_dir/thrift/parse/parse.cc" \
        "$source_dir/thrift/parse/t_typedef.cc" \
        "$source_dir/thrift/thriftl.cc" \
        "$source_dir/thrift/thrifty.cc" \
        -o "$work/thrift-js"
fi
[[ $("$work/thrift-js" --version) == 'Thrift version 0.25.0' ]]
"$work/thrift-js" --gen js:node,bigint -out "$bindings/generated-bigint" "$bindings/version-echo.thrift"
CONTAINER
