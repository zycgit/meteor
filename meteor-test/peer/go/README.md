# Official Go Order peers

`main.go` and `thrift_peer.go` implement independent providers and consumers with
grpc-go, Connect Go and Apache Thrift Go. Their Go module has no Meteor dependency.
The Java scenarios use interfaces and DTOs from
`meteor-test/peer/java/contracts/src/main/java/net/hasor/example`; Meteor
implementations belong to `peer/java/meteor/src/main/java`.

## Docker execution

The host needs JDK 17, Docker and Compose. Use the Gradle 9.5 Wrapper from the
repository root:

```sh
./gradlew :meteor-test:dockerTest \
  --tests 'net.hasor.realmeteor.protocol.grpc.go.*' \
  --tests 'net.hasor.realmeteor.protocol.connect.go.*' \
  --tests 'net.hasor.realmeteor.protocol.thrift.go.*'
```

Windows uses `.\gradlew.bat :meteor-test:dockerTest` with the same options.
The host Gradle task manages Compose; Go compiles the peer once inside its
architecture-specific runtime container. Each scenario starts an independent
`/opt/meteor-peer/go/order-peer` process. Java owns that process; Compose owns
the shared container.
See the [Docker test plan](../../../document/testing/METEOR_TEST_DOCKER_PLAN.md)
for the shared Gradle entry and acceptance scope.

Providers listen on `0.0.0.0` with an allocated port and write their address to
`--ready-file`. Java replaces the listen host with that container's Docker DNS
name. Consumers receive the target container's address, write JSON to
`--result-file`, and exit nonzero on a mismatch. Ready files, results and logs use
the shared workspace; business traffic crosses the container network.

## Tools, SDKs and contracts

The Dockerfile selects Go `1.27.2`; `go.mod` and `go.sum` lock:

- grpc-go `v1.84.0`.
- Connect Go `v1.19.1`.
- protobuf-go `v1.36.12`.
- Apache Thrift Go `v0.25.0`.

The version scenarios under `net.hasor.realmeteor.compatibility.go` additionally
select these isolated SDK graphs:

| SDK | Selected releases | Version baseline |
| --- | --- | --- |
| grpc-go | 1.84.0, 1.85.0 | Protobuf, HTTP/2 |
| Connect Go | 1.19.1, 1.21.1 | Protobuf, HTTP/1.1 |
| Hprose Go | 2.0.6+incompatible, 3.0.16 | Hprose, HTTP/1.1 |
| Apache Thrift Go | 0.13.0, 0.14.2, 0.18.1, 0.25.0 | Binary, framed TCP |
| Apache Dubbo-Go | 1.5.9, 3.3.2 | Dubbo, Hessian2 |

Each version runs Unicode success and application failure in both directions.
Protobuf cases also carry the shared order's scalar and binary fields. These
version baselines use plain transport; the existing current-release scenarios
retain their TLS and full type matrix. SDK packages remain unmodified.
Thrift 0.18.1 and 0.25.0 additionally exercise the native UUID wire type in both
directions; older releases do not expose that type.

```sh
./gradlew :meteor-test:dockerTest --tests '*Go*SdkVersionCompatibilityTest'
```

Build readiness checks the actual linked module version with `go version -m`.
The 1.5.9 Dubbo-Go package retains an older 1.5.8 banner string; its verified
module identity is `github.com/apache/dubbo-go@v1.5.9`.

`GOTOOLCHAIN=local` disables implicit toolchain selection. Protobuf contracts come
from `meteor-test/resource/protocol/proto/order.proto`; the Thrift contract is
`meteor-test/resource/protocol/thrift/order.thrift`. Committed bindings are under
`gen/orderpb` and `gen/thriftorder`, with the same `save(Order)` and
`find(ThriftOrderQuery)` business semantics as Java.

### Developer-only regeneration

Normal test execution uses the committed bindings. To change a Protobuf contract,
developers provide Go `1.27.2` and `protoc`:

```sh
bash meteor-test/peer/go/regenerate.sh \
  --go /absolute/path/to/go --protoc /absolute/path/to/protoc
```

The script installs the pinned official generators into
`meteor-test/build/go-generators`: `protoc-gen-go v1.36.12`,
`protoc-gen-go-grpc v1.6.2` and `protoc-gen-connect-go v1.19.1`. This manual command
is separate from the Docker test launcher.

Regenerate Thrift bindings with Apache Thrift Compiler `0.25.0`, including its Go
generator:

```sh
bash meteor-test/peer/go/regenerate-thrift.sh --compiler /absolute/path/to/thrift
```

Only the generated package name and `skip_remote` option are selected; the
Processor, Client and data types retain their official implementation.

## Scenarios

Each protocol has provider and consumer cases under plain TCP/HTTP, TLS and mutual
TLS. Certificates come from `meteor-test/resource/certs/acceptance`; TLS checks the CA and Docker DNS
subject alternative name, and mutual TLS requires the acceptance client certificate.

Thrift selects six profiles named
`thrift-<binary|compact|json>-<framed|unframed>`, with the same five order scenarios,
two directions and three security modes.

| Scenario | id | description | attachment |
| --- | --- | --- | --- |
| `unicode` | 101 | `订单-你好-🌍-café` | `00 01 7f 80 ff` |
| `boundary` | 2147483647 | `boundary` | `00 01 7f 80 ff` |
| `negative-boundary` | -2147483648 | `negative-boundary` | `00 01 7f 80 ff` |
| `empty` | 0 | empty string | empty byte array |
| `missing` | 404 | lookup only | no saved order |

The save scenario checks the `save` reply and the subsequent `find` result. For
non-optional proto3 string and bytes fields, absent wire values decode to an empty
string and an empty byte array. Optional presence remains a separate contract.

A missing Go order returns gRPC `NotFound` or Connect `not_found`. A missing Meteor
order throws `IllegalArgumentException`; gRPC maps it to `Internal`, while Connect
uses `invalid_argument`. Thrift maps an undeclared failure to
`TApplicationException.INTERNAL_ERROR`. Its official Go Processor prefixes the
message with `Internal error processing find: `; Meteor preserves the original
order-not-found message.
