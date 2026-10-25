#!/usr/bin/env bash
# Debian's libjson-rpc-cpp SDK exposes C++14-era exception specifications.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
sdk_prefix=${2:-}
mkdir -p "$output_dir"
sdk_flags=()
if [[ -n "$sdk_prefix" ]]; then
    # Keep the selected SDK first; JsonCpp, curl and microhttpd remain OS dependencies.
    read -r -a sdk_flags <<< "$(pkg-config --cflags --libs jsoncpp)"
    sdk_flags+=(-I "$sdk_prefix/include" -L "$sdk_prefix/lib" "-Wl,-rpath,$sdk_prefix/lib")
    sdk_flags+=(-ljsonrpccpp-client -ljsonrpccpp-server -ljsonrpccpp-common)
else
    read -r -a sdk_flags <<< "$(pkg-config --cflags --libs libjsonrpccpp-client libjsonrpccpp-server)"
fi
g++ -std=c++14 -O2 -Wall -Wextra -Werror -Wno-deprecated -pthread \
    "$source_dir/echo_peer.cpp" "${sdk_flags[@]}" -o "$output_dir/echo-peer"
