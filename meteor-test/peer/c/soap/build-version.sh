#!/usr/bin/env bash
# Build one source release in a private prefix alongside the system gSOAP baseline.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:?Output directory is required}
build_jobs=${2:-4}
mkdir -p -- "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
prefix="$output_dir/prefix"
archive="$output_dir/gsoap_2.8.146.zip"
url=https://master.dl.sourceforge.net/project/gsoap2/gsoap_2.8.146.zip
digest=821af2ecfb93ca9dbd9bdae67fa0d57f9165fb4252bb40e23cd510dc4d49ec98

# The upstream release and published SHA-256 are pinned, including its code generators.
curl --fail --location --show-error --connect-timeout 30 "$url" -o "$archive"
printf '%s  %s\n' "$digest" "$archive" | sha256sum --check -
mkdir -p -- "$output_dir/source" "$prefix/bin" "$prefix/lib" "$prefix/include" "$prefix/share/gsoap"
unzip -q -o "$archive" -d "$output_dir/source"
sdk_source="$output_dir/source/gsoap-2.8"
(
    cd -- "$sdk_source"
    bash ./configure --prefix="$prefix" --disable-ssl --disable-samples --disable-dependency-tracking
    make -C gsoap -j "$build_jobs" libgsoap.a libgsoap++.a
    make -C gsoap/src soapcpp2_yacc.h
    make -C gsoap/src -j "$build_jobs" soapcpp2
    make -C gsoap/wsdl wsdlC.cpp
    make -C gsoap/wsdl -j "$build_jobs" wsdl2h
)

# Install only the peer's static runtimes and generators; system /usr remains unchanged.
cp -- "$sdk_source/gsoap/src/soapcpp2" "$sdk_source/gsoap/wsdl/wsdl2h" "$prefix/bin/"
cp -- "$sdk_source/gsoap/libgsoap.a" "$sdk_source/gsoap/libgsoap++.a" "$prefix/lib/"
cp -- "$sdk_source/gsoap/stdsoap2.h" "$prefix/include/"
cp -R -- "$sdk_source/gsoap/import" "$sdk_source/gsoap/WS" "$prefix/share/gsoap/"
cp -- "$sdk_source/gsoap/typemap.dat" "$prefix/share/gsoap/"
grep -q '^#define GSOAP_VERSION 208146$' "$prefix/include/stdsoap2.h"
printf 'gsoap 2.8.146\nsha256 %s\nsource %s\n' "$digest" "$url" > "$prefix/sdk-version.txt"
cp -- "$prefix/sdk-version.txt" "$output_dir/sdk-version.txt"

# C and C++ bindings are generated independently from the same test-owned contract.
SOAP_SDK_PREFIX="$prefix" bash "$source_dir/build.sh" --output "$output_dir/peer/c"
SOAP_SDK_PREFIX="$prefix" bash "$source_dir/../../cpp/soap/build.sh" --output "$output_dir/peer/cpp"
