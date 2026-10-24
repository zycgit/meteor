# Official Go Order peers

`main.go` and `thrift_peer.go` implement independent providers and consumers with
grpc-go, Connect Go and Apache Thrift Go. Their Go module has no Meteor dependency.
The Java scenarios reuse `met-example`'s ordinary order interface, implementation
and DTOs.

## Docker execution

Run from the repository root:

```sh
./meteor-test/docker/run.sh -- :meteor-test:test \
  --tests 'net.hasor.realmeteor.protocol.grpc.go.*' \
  --tests 'net.hasor.realmeteor.protocol.connect.go.*' \
  --tests 'net.hasor.realmeteor.protocol.thrift.go.*' \
  --no-daemon --console=plain --rerun-tasks
```

Windows uses `meteor-test/docker/run.ps1`. The launcher builds an
architecture-specific Go image; the host needs Docker with Compose. The compiler
and module cache stay in the image build. Runtime peers use
`/opt/meteor-peer/go/order-peer`, and Java owns each peer container's shutdown.
See the [Docker test plan](../../../../document/testing/METEOR_TEST_DOCKER_PLAN.md)
for the shared launcher and acceptance scope.

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

`GOTOOLCHAIN=local` disables implicit toolchain selection. Protobuf contracts come
from `met-example/src/main/resources/proto/order.proto`; the Thrift contract is
`meteor-test/src/test/thrift/order.thrift`. Committed bindings are under
`gen/orderpb` and `gen/thriftorder`, with the same `save(Order)` and
`find(OrderQuery)` business semantics as Java.

### Developer-only regeneration

Normal test execution uses the committed bindings. To change a Protobuf contract,
developers provide Go `1.27.2` and a Python interpreter with `grpc_tools.protoc`:

```sh
python3 meteor-test/src/test/go/regenerate.py \
  --go /absolute/path/to/go --protoc-python /absolute/path/to/python
```

The script installs the pinned official generators into
`meteor-test/build/go-generators`: `protoc-gen-go v1.36.12`,
`protoc-gen-go-grpc v1.6.2` and `protoc-gen-connect-go v1.19.1`. This manual command
is separate from the Docker test launcher.

Regenerate Thrift bindings with Apache Thrift Compiler `0.25.0`, including its Go
generator:

```sh
python3 meteor-test/src/test/go/regenerate-thrift.py --compiler /absolute/path/to/thrift
```

Only the generated package name and `skip_remote` option are selected; the
Processor, Client and data types retain their official implementation.

## Scenarios

Each protocol has provider and consumer cases under plain TCP/HTTP, TLS and mutual
TLS. Certificates come from `grpc/tls/acceptance`; TLS checks the CA and Docker DNS
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
