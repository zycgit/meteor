#!/usr/bin/env bash
# Regenerate the official SOAP client from the test-owned WSDL and imported XSD.
set -euo pipefail

source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
test_dir=$(cd -- "$source_dir/../.." && pwd)
go_command=go
while (($#)); do
    case "$1" in
        --go) go_command=$2; shift 2 ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done
export GOTOOLCHAIN=local GOWORK=off CGO_ENABLED=0

# Combine shared inputs for the generator's relative XSD imports.
contract_dir="$test_dir/build/go-soap-regeneration"
mkdir -p "$contract_dir"
cp -- "$test_dir/resource/protocol/wsdl/echo.wsdl" "$contract_dir/echo.wsdl"
cp -- "$test_dir/resource/protocol/xsd/echo-types.xsd" "$contract_dir/echo-types.xsd"

cd -- "$source_dir"
"$go_command" run github.com/hooklift/gowsdl/cmd/gowsdl@v0.5.0 \
    -p soapecho -o echo.go -d "$source_dir/gen" "$contract_dir/echo.wsdl"

# Record the exact generator and inputs next to its untouched generated implementation.
generated="$source_dir/gen/soapecho/echo.go"
annotated=$(mktemp "$source_dir/gen/soapecho/.echo.XXXXXX")
{
    printf '%s\n' \
        '// Generator: github.com/hooklift/gowsdl/cmd/gowsdl v0.5.0.' \
        '// Inputs: meteor-test/resource/protocol/wsdl/echo.wsdl and meteor-test/resource/protocol/xsd/echo-types.xsd.' \
        '// Regenerate inside the Go toolchain container: bash meteor-test/peer/go/regenerate-soap.sh.'
    cat -- "$generated"
} > "$annotated"
mv -- "$annotated" "$generated"
"$go_command" fmt ./gen/soapecho
