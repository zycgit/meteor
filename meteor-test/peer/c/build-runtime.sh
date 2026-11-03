#!/usr/bin/env bash
# Build C and C++ ONC RPC peers inside their shared native toolchain.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
peer_dir=/opt/meteor-peer
prepare_only=false
while (($#)); do
    case "$1" in
        --output) peer_dir=$2; shift 2 ;;
        --prepare-only) prepare_only=true; shift ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done
mkdir -p "$peer_dir/native"
peer_dir=$(cd -- "$peer_dir" && pwd)
output_dir="$peer_dir/native"
ready_file="$peer_dir/runtime-native.ready.json"
rm -f -- "$ready_file"

# Both languages use rpcgen/libtirpc while keeping separate executable entrypoints.
bash "$source_dir/build-native.sh" --output "$output_dir"
make -C "$source_dir/order" "BUILD_DIR=$output_dir/order"

# Native SDK bindings are generated from the shared contracts into the output volume.
bash "$source_dir/../cpp/grpc/build.sh" "$output_dir/cpp/grpc"
bash "$source_dir/../cpp/thrift/build.sh" "$output_dir/cpp/thrift"
bash "$source_dir/soap/build.sh" --output "$output_dir/soap-c"
bash "$source_dir/../cpp/soap/build.sh" --output "$output_dir/soap-cpp"
bash "$source_dir/xmlrpc/build.sh" "$output_dir/xmlrpc/c"
bash "$source_dir/../cpp/xmlrpc/build.sh" "$output_dir/xmlrpc/cpp"
bash "$source_dir/../cpp/jsonrpc/build.sh" "$output_dir/jsonrpc/cpp"
bash "$source_dir/thrift/build.sh" "$output_dir/c/thrift"
bash "$source_dir/grpc/build.sh" "$output_dir/c/grpc"
bash "$source_dir/jsonrpc/build.sh" "$output_dir/c/jsonrpc"
bash "$source_dir/../cpp/hprose/build.sh" "$output_dir/cpp/hprose"
bash "$source_dir/../cpp/connect/build.sh" "$output_dir/connect/cpp"
bash "$source_dir/../cpp/hessian/build.sh" "$output_dir/hessian/cpp"

# SDK prefixes belong to the image; every business peer is rebuilt from the current source snapshot.
versions_dir=/opt/meteor-toolchain/sdk-versions
require_sdk_file() {
    test -e "$1" || {
        printf 'Missing prepared native SDK file: %s; rebuild the SDK image\n' "$1" >&2
        exit 2
    }
}
for sdk in grpc thrift soap xmlrpc oncrpc jsonrpc; do
    for release in "$versions_dir/$sdk/"*; do
        version=$(basename "$release")
        destination="$output_dir/versions/$sdk/$version"
        mkdir -p "$destination"
        case "$sdk" in
            grpc)
                require_sdk_file "$release/prefix/include/grpcpp/grpcpp.h"
                bash "$source_dir/../cpp/grpc/build-peers.sh" "$destination" 4 "$release/prefix"
                cp -a "$destination/peer/." "$destination/"
                ;;
            thrift)
                require_sdk_file "$release/install/include/thrift/Thrift.h"
                bash "$source_dir/../cpp/thrift/build.sh" "$destination/cpp" "$release/install"
                bash "$source_dir/thrift/build.sh" "$destination/c" "$release/install"
                ;;
            soap)
                require_sdk_file "$release/prefix/include/stdsoap2.h"
                SOAP_SDK_PREFIX="$release/prefix" bash "$source_dir/soap/build.sh" --output "$destination/c"
                SOAP_SDK_PREFIX="$release/prefix" bash "$source_dir/../cpp/soap/build.sh" --output "$destination/cpp"
                ;;
            xmlrpc)
                require_sdk_file "$release/prefix/bin/xmlrpc-c-config"
                XMLRPC_C_CONFIG="$release/prefix/bin/xmlrpc-c-config" bash "$source_dir/xmlrpc/build.sh" "$destination/c"
                XMLRPC_C_CONFIG="$release/prefix/bin/xmlrpc-c-config" bash "$source_dir/../cpp/xmlrpc/build.sh" "$destination/cpp"
                ;;
            oncrpc)
                require_sdk_file "$release/prefix/include/tirpc/rpc/rpc.h"
                make -B -C "$source_dir/order" "BUILD_DIR=$destination" \
                    "TIRPC_INCLUDE=$release/prefix/include/tirpc" "LDLIBS=$release/prefix/lib/libtirpc.a -pthread"
                ;;
            jsonrpc)
                require_sdk_file "$release/install/include/jsonrpccpp/client.h"
                bash "$source_dir/../cpp/jsonrpc/build.sh" "$destination/cpp" "$release/install"
                ;;
        esac
        find "$destination" -type f -executable -exec ldd -- {} + > "$destination/runtime-linked-libraries.txt"
    done
done

# Record the system SDK versions next to the archived preparation evidence.
preparation_dir="$source_dir/../../build/preparation"
mkdir -p "$preparation_dir"
dpkg-query -W -f='${Package} ${Version}\n' libgrpc++-dev libprotobuf-dev \
    libthrift-dev gsoap libgsoap-dev libxmlrpc-core-c3-dev libxmlrpc-c++8-dev \
    libjsonrpccpp-dev libthrift-c-glib-dev libglib2.0-dev libgrpc-dev \
    libprotobuf-c-dev protobuf-c-compiler libtirpc-dev rpcsvc-proto libcurl4-openssl-dev \
    > "$preparation_dir/native-sdk-versions.txt"
cp /opt/meteor-loams/sdk-sources.txt "$preparation_dir/loams-sdk-sources.txt"
mkdir -p "$preparation_dir/native-sdk-versions"
find "$versions_dir" -type f \
    \( -name 'sdk-version.txt' -o -name 'sdk-runtime.txt' -o -name 'peer-runtime.txt' -o -name 'sdk-sources.txt' \
        -o -name '*build.log' -o -name 'ldd.txt' -o -name '*libraries.txt' \) \
    | while IFS= read -r entry; do
        relative=${entry#"$versions_dir/"}
        mkdir -p "$preparation_dir/native-sdk-versions/$(dirname "$relative")"
        cp "$entry" "$preparation_dir/native-sdk-versions/$relative"
    done
find "$output_dir/versions" -type f -executable -exec sha256sum -- {} + \
    > "$preparation_dir/native-sdk-version-binaries.sha256"

# NXN reads actual installed SDK identities and built executables, not a second version table.
nxn_directory="$preparation_dir/sdk-versions/native"
mkdir -p "$nxn_directory"
latest_grpc=$(printf '%s\n' "$output_dir/versions/grpc/"* | sort -V | tail -n 1)
latest_thrift=$(printf '%s\n' "$output_dir/versions/thrift/"* | sort -V | tail -n 1)
latest_oncrpc=$(printf '%s\n' "$output_dir/versions/oncrpc/"* | sort -V | tail -n 1)
test -x "$latest_grpc/cpp/order-peer"
test -x "$latest_thrift/cpp/order-peer"
test -x "$latest_oncrpc/c/provider"
test -x "$latest_oncrpc/cpp/provider"
jq -n --arg output "$output_dir" \
    --arg tirpc "$(pkg-config --modversion libtirpc)" \
    --arg grpc "$(pkg-config --modversion grpc++)" \
    --arg thrift "$(pkg-config --modversion thrift)" \
    --arg grpc_latest "$latest_grpc" --arg thrift_latest "$latest_thrift" --arg oncrpc_latest "$latest_oncrpc" \
    '{profiles:{
      cOncrpc:{version:$tirpc,sdk:"libtirpc",executable:($output+"/order/c")},
      cppOncrpc:{version:$tirpc,sdk:"libtirpc",executable:($output+"/order/cpp")},
      cOncrpcLatest:{version:($oncrpc_latest|split("/")|last),sdk:"libtirpc",executable:($oncrpc_latest+"/c")},
      cppOncrpcLatest:{version:($oncrpc_latest|split("/")|last),sdk:"libtirpc",executable:($oncrpc_latest+"/cpp")},
      grpcCurrent:{version:$grpc,sdk:"gRPC C++",executable:($output+"/cpp/grpc/order-peer")},
      grpcLatest:{version:($grpc_latest|split("/")|last),sdk:"gRPC C++",executable:($grpc_latest+"/cpp/order-peer")},
      thriftCurrent:{version:$thrift,sdk:"Apache Thrift C++",executable:($output+"/cpp/thrift/order-peer")},
      thriftLatest:{version:($thrift_latest|split("/")|last),sdk:"Apache Thrift C++",executable:($thrift_latest+"/cpp/order-peer")}
    }}' > "$nxn_directory/nxn-runtimes.json"
jq -n --arg manifest "$output_dir/native-peer-runtime.json" \
    '{runtime:"native",manifest:$manifest}' > "$ready_file"
printf '%s\n' "$ready_file"
if [[ $prepare_only == false ]]; then
    exec sleep infinity
fi
