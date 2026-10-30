#!/usr/bin/env bash
# Generate a C++ SOAP proxy/service with native std::string data binding.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ $# == 2 && $1 == --output ]] || { printf 'Usage: %s --output DIRECTORY\n' "$0" >&2; exit 2; }
mkdir -p -- "$2"
output_dir=$(cd -- "$2" && pwd)
contract="$source_dir/../../../resource/protocol/wsdl/echo.wsdl"

# A private SDK prefix selects both generators and runtime, leaving system tools intact.
sdk_prefix=${SOAP_SDK_PREFIX:-}
wsdl2h=wsdl2h
soapcpp2=soapcpp2
imports=/usr/share/gsoap/import
generator_options=()
compiler_options=(-lgsoap++)
if [[ -n $sdk_prefix ]]; then
    wsdl2h="$sdk_prefix/bin/wsdl2h"
    soapcpp2="$sdk_prefix/bin/soapcpp2"
    imports="$sdk_prefix/share/gsoap/import"
    generator_options=(-t "$sdk_prefix/share/gsoap/typemap.dat")
    compiler_options=(-I "$sdk_prefix/include" "$sdk_prefix/lib/libgsoap++.a")
fi

# C++ generates its own proxy, service and serializers instead of borrowing C ABI.
"$wsdl2h" "${generator_options[@]}" -I "$source_dir/../../../resource/protocol/xsd" \
    -o "$output_dir/echo.h" "$contract"
"$soapcpp2" -j -L -x -I"$imports" -d "$output_dir" "$output_dir/echo.h"
g++ -std=c++17 -O2 -Wall -Wextra -I "$output_dir" "$source_dir/peer.cpp" \
    "$output_dir/soapC.cpp" "$output_dir/soapEchoBindingProxy.cpp" \
    "$output_dir/soapEchoBindingService.cpp" "${compiler_options[@]}" -o "$output_dir/peer"
if [[ -n $sdk_prefix ]]; then
    cp -- "$sdk_prefix/sdk-version.txt" "$output_dir/sdk-version.txt"
else
    dpkg-query -W -f='${Package} ${Version}\n' gsoap libgsoap-dev > "$output_dir/sdk-version.txt"
fi
