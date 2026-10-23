# Official Go Order peers

`main.go` provides standalone official grpc-go, Connect Go and Apache Thrift Go
providers/consumers. Thrift adapters live in `thrift_peer.go`.
Its Go module has no Meteor dependency. Java starts a separate OS process for every
scenario and owns its shutdown. Providers bind `127.0.0.1:0` and atomically write
their actual bound address to `--ready-file`; consumers verify a single scenario,
write JSON to `--result-file`, and exit nonzero on any mismatch.

The contracts come directly from `met-example/src/main/resources/proto/order.proto`
and `meteor-test/src/test/thrift/order.thrift`. Generated Go files are committed
under `gen/orderpb` and `gen/thriftorder`. The Thrift test contract matches the annotated
`save(Order)` and `find(OrderQuery)` signatures. The Java side directly reuses
`met-example`'s `OrderDemoService`, `OrderDemoServiceImpl`, `Order` and `OrderQuery`.

## Locked tools and SDKs

* Go `1.27.2`; `prepare.py` downloads only into `meteor-test/build/tools` and verifies
  the archive against the SHA256 in [official Go release metadata](https://go.dev/dl/?mode=json&include=all).
* [grpc-go `v1.84.0`](https://github.com/grpc/grpc-go/releases/tag/v1.84.0).
* [Connect Go `v1.19.1`](https://github.com/connectrpc/connect-go/releases/tag/v1.19.1).
* [protobuf-go `v1.36.12`](https://github.com/protocolbuffers/protobuf-go/releases/tag/v1.36.12).
* [Apache Thrift Go `v0.25.0`](https://github.com/apache/thrift/tree/v0.25.0/lib/go/thrift),
  with bindings produced by Apache Thrift Compiler `0.25.0`.
* [`protoc-gen-go-grpc v1.6.2`](https://github.com/grpc/grpc-go/tree/cmd/protoc-gen-go-grpc/v1.6.2/cmd/protoc-gen-go-grpc).

`go.mod` and `go.sum` lock SDK dependencies. `GOTOOLCHAIN=local` disables implicit
toolchain downloads; module and compiler caches also stay in `meteor-test/build`.
The generated contract has no separate SourceSet or copied Java classes.

From the repository root, build the local binary with:

```sh
python3 meteor-test/src/test/go/prepare.py --build-dir meteor-test/build
```

`gradle/native-peers.gradle` exposes `buildGoOrderPeer` and supplies the Java tests with
`go.order.peer.binary`, `go.order.peer.cert-dir` and `go.order.peer.work-dir`.
It is applied by the test host's build script. Normal test execution uses the host's
standard `test` output and the built binary.

To regenerate bindings, prepare the host's Python gRPC venv, then run:

```sh
python3 meteor-test/src/test/go/regenerate.py
```

Pass `--protoc-python /absolute/path/to/python` to use another Python executable
with `grpc_tools.protoc`. Regeneration installs only the three pinned official Go
generators into `meteor-test/build/go-generators`.

Regenerate Thrift bindings with the official compiler of the same version. It must
include the Go generator; this command does not build another compiler or run during normal tests:

```sh
python3 meteor-test/src/test/go/regenerate-thrift.py --compiler /absolute/path/to/thrift
```

Only the Go package name and `skip_remote` generator option are selected. The generated
Processor, Client and data types retain their official implementation.

## Scenarios

Each protocol has separate provider and consumer cases under plain TCP/HTTP, TLS
and mutual TLS. Transport certificates come from the existing test resources
`grpc/tls/acceptance`; TLS verifies the CA and IP subject alternative name, and
mutual TLS requires the acceptance client certificate.

Thrift selects six wire profiles named
`thrift-<binary|compact|json>-<framed|unframed>`. Each profile uses the same five
order scenarios, provider/consumer directions and plain/TLS/mTLS modes. Providers
report `host:port`, and consumers accept the same target form.

| Scenario | id | description | attachment |
| --- | --- | --- | --- |
| `unicode` | 101 | `订单-你好-🌍-café` | `00 01 7f 80 ff` |
| `boundary` | 2147483647 | `boundary` | `00 01 7f 80 ff` |
| `negative-boundary` | -2147483648 | `negative-boundary` | `00 01 7f 80 ff` |
| `empty` | 0 | empty | empty |
| `missing` | 404 | lookup only | no saved order |

The provider scenario calls `save`, checks its reply, then calls `find` and checks
the same order. The consumer does the same through Meteor's public subscription
API. On proto3 default replies, Meteor's existing JSON conversion omits default
string/bytes members, so the plain Java DTO receives null for these two fields;
the tests document that behavior.

A missing Go order returns gRPC `NotFound` or Connect `not_found`. A missing Meteor
order throws the existing `IllegalArgumentException`: current gRPC maps the
invocation status to `Internal`; Connect preserves it as `invalid_argument`.
The official Go consumer checks those exact codes and the missing id in the error.
Thrift maps an undeclared order failure to `TApplicationException.INTERNAL_ERROR`.
The official Go Processor prefixes the message with `Internal error processing find: `;
the Meteor endpoint preserves the original order-not-found message. Thrift's optional
description and attachment fields retain the demo's explicitly supplied empty values.
