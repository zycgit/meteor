# Meteor RPC 测试报告

结果：通过。

skipError：`true`。

用例筛选：全部。

通过 7829 项，失败 0 项，跳过 0 项，已确认未执行 0 项。

详细结果：[summary.json](build/docker-runs/meteor-test/build/interoperability/summary.json)

## SDK 版本兼容

— 表示本轮未执行该方向，不表示 SDK 不支持。

| 语言 | 协议 | SDK | 版本 | 运行图 | SDK → Meteor | Meteor → SDK |
| --- | --- | --- | --- | --- | --- | --- |
| go | connect | Connect-Go | 1.19.1 | protobuf-http1 | 通过 | 通过 |
| go | connect | Connect-Go | 1.21.1 | protobuf-http1 | 通过 | 通过 |
| go | grpc | grpc-go | 1.84.0 | protobuf-http2 | 通过 | 通过 |
| go | grpc | grpc-go | 1.85.0 | protobuf-http2 | 通过 | 通过 |
| go | dubbo | Apache-Dubbo-Go | 1.5.9 | dubbo-hessian2 | 通过 | 通过 |
| go | dubbo | Apache-Dubbo-Go | 3.3.2 | dubbo-hessian2 | 通过 | 通过 |
| go | hprose | Hprose-Go | 2.0.6+incompatible | http1-hprose | 通过 | 通过 |
| go | hprose | Hprose-Go | 3.0.16 | http1-hprose | 通过 | 通过 |
| go | thrift | Apache-Thrift | 0.13.0 | binary-framed | 通过 | 通过 |
| go | thrift | Apache-Thrift | 0.14.2 | binary-framed | 通过 | 通过 |
| go | thrift | Apache-Thrift | 0.18.1 | binary-framed | 通过 | 通过 |
| go | thrift | Apache-Thrift | 0.25.0 | binary-framed | 通过 | 通过 |
| go | thrift | Apache-Thrift | 0.18.1 | uuid-binary-framed | 通过 | 通过 |
| go | thrift | Apache-Thrift | 0.25.0 | uuid-binary-framed | 通过 | 通过 |
| c | grpc | gRPC-Core | 1.51.1 | debian-baseline | 通过 | 通过 |
| c | grpc | gRPC-Core | 1.85.0 | upstream-latest | 通过 | 通过 |
| c | thrift | Apache-Thrift | 0.17.0 | debian-baseline | 通过 | 通过 |
| c | thrift | Apache-Thrift | 0.25.0 | upstream-latest | 通过 | 通过 |
| cpp | grpc | gRPC-Core | 1.51.1 | debian-baseline | 通过 | 通过 |
| cpp | grpc | gRPC-Core | 1.85.0 | upstream-latest | 通过 | 通过 |
| cpp | thrift | Apache-Thrift | 0.17.0 | debian-baseline | 通过 | 通过 |
| cpp | thrift | Apache-Thrift | 0.25.0 | upstream-latest | 通过 | 通过 |
| cpp | jsonrpc | libjson-rpc-cpp | 0.7.0 | legacy-debian | 通过 | 通过 |
| cpp | jsonrpc | libjson-rpc-cpp | 1.4.1 | upstream-latest | 通过 | 通过 |
| c | oncrpc | libtirpc | 1.3.8 | upstream-latest | 通过 | 通过 |
| cpp | oncrpc | libtirpc | 1.3.8 | upstream-latest | 通过 | 通过 |
| node | connect | Connect-ES | 1.7.0 | connect-1.7.0 | 通过 | 通过 |
| node | connect | Connect-ES | 2.3.0 | connect-2.3.0 | 通过 | 通过 |
| node | dubbo | dubbo2.js | 1.0.4 | dubbo-1.0.4 | 通过 | — |
| node | dubbo | dubbo2.js | 2.3.9 | dubbo-2.3.9 | 通过 | — |
| node | jsonrpc | Jayson | 2.1.2 | jsonrpc-2.1.2 | 通过 | 通过 |
| node | jsonrpc | Jayson | 3.7.0 | jsonrpc-3.7.0 | 通过 | 通过 |
| node | jsonrpc | Jayson | 4.3.0 | jsonrpc-4.3.0 | 通过 | 通过 |
| node | jsonrpc | Jayson | 5.0.0 | jsonrpc-5.0.0 | 通过 | 通过 |
| node | soap | node-soap | 0.45.0 | soap-0.45.0 | 通过 | 通过 |
| node | soap | node-soap | 1.14.0 | soap-1.14.0 | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-binary-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-binary-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-binary-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-compact-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-compact-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-compact-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-json-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-json-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-json-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-binary-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-binary-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-binary-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-compact-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-compact-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-compact-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-json-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-json-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-json-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-binary-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-binary-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-binary-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-compact-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-compact-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-compact-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-json-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-json-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-json-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-binary-bigint-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-binary-bigint-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-binary-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-binary-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-binary-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-compact-bigint-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-compact-bigint-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-compact-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-compact-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-compact-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-json-bigint-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-json-bigint-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-json-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-json-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-json-string | 通过 | 通过 |
| c | xmlrpc | xmlrpc-c | 1.33.14 | legacy-debian | 通过 | 通过 |
| c | xmlrpc | xmlrpc-c | 1.64.04 | upstream-latest | 通过 | 通过 |
| cpp | xmlrpc | xmlrpc-c | 1.33.14 | legacy-debian | 通过 | 通过 |
| cpp | xmlrpc | xmlrpc-c | 1.64.04 | upstream-latest | 通过 | 通过 |
| java | connect | Connect Kotlin | 0.10.0 | connectCurrent | 通过 | — |
| java | connect | Connect Kotlin | 0.4.0 | connectLegacy | 通过 | — |
| java | connect | Connect Kotlin | 0.5.1 | connect | 通过 | — |
| java | dubbo | Alibaba Dubbo | 2.6.12 | dubboClassic | 通过 | 通过 |
| java | dubbo | Apache Dubbo | 2.7.23 | dubboLegacy | 通过 | 通过 |
| java | dubbo | Apache Dubbo | 3.3.6 | dubbo | 通过 | 通过 |
| java | grpc | gRPC-Java | 1.85.1 | grpcCurrent | 通过 | 通过 |
| java | grpcweb | Connect Kotlin | 0.10.0 | connectCurrent | 通过 | — |
| java | grpcweb | Connect Kotlin | 0.4.0 | connectLegacy | 通过 | — |
| java | grpcweb | Connect Kotlin | 0.5.1 | connect | 通过 | — |
| java | hessian | Caucho Hessian | 4.0.51 | hessianLegacy | 通过 | 通过 |
| java | hessian | Caucho Hessian | 4.0.66 | hessianRecent | 通过 | 通过 |
| java | hprose | Hprose Java | 2.0.38 | hprose | 通过 | 通过 |
| java | jsonrpc | jsonrpc4j | 1.7 | jsonrpc | 通过 | 通过 |
| java | oncrpc | RemoteTea | 1.1.6 | oncrpc | 通过 | 通过 |
| java | soap | Metro JAX-WS | 2.3.7 | soapJavax | 通过 | 通过 |
| java | soap | Metro JAX-WS | 3.0.2 | soapLegacy | 通过 | 通过 |
| java | soap | Metro JAX-WS | 4.0.5 | soap | 通过 | 通过 |
| java | thrift | Apache Thrift | 0.10.0 | thriftClassic | 通过 | 通过 |
| java | thrift | Apache Thrift | 0.13.0 | thriftPreLayered | 通过 | 通过 |
| java | thrift | Apache Thrift | 0.16.0 | thriftLayered | 通过 | 通过 |
| java | thrift | Apache Thrift | 0.22.0 | thriftLegacy | 通过 | 通过 |
| java | thrift | Apache Thrift | 0.25.0 | thrift | 通过 | 通过 |
| java | triple | Apache Dubbo | 3.2.20 | tripleLegacy | 通过 | 通过 |
| java | triple | Apache Dubbo | 3.3.6 | dubbo | 通过 | 通过 |
| java | xmlrpc | Apache XML-RPC | 3.1.3 | xmlrpc | 通过 | 通过 |
| java | connect | Connect RPC Java | 0.2.1 | connectServer | — | 通过 |
| python | connect | connectrpc | 0.10.1 | google | 通过 | 通过 |
| python | connect | connectrpc | 0.11.1 | google | 通过 | 通过 |
| python | connect | connectrpc | 0.11.1 | native | 通过 | 通过 |
| python | connect | connectrpc | 0.13.0 | google | 通过 | 通过 |
| python | connect | connectrpc | 0.13.0 | native | 通过 | 通过 |
| python | grpc-web | connectrpc | 0.10.1 | google | 通过 | 通过 |
| python | grpc-web | connectrpc | 0.11.1 | google | 通过 | 通过 |
| python | grpc-web | connectrpc | 0.11.1 | native | 通过 | 通过 |
| python | grpc-web | connectrpc | 0.13.0 | google | 通过 | 通过 |
| python | grpc-web | connectrpc | 0.13.0 | native | 通过 | 通过 |
| python | grpc | grpcio | 1.85.0 | protobuf | 通过 | 通过 |
| browser | grpc-web | grpc-web | 1.5.0 | grpcweb-1.5.0-binary | 通过 | — |
| browser | grpc-web | grpc-web | 1.5.0 | grpcweb-1.5.0-text | 通过 | — |
| browser | grpc-web | grpc-web | 2.1.1 | grpcweb-2.1.1-binary | 通过 | — |
| browser | grpc-web | grpc-web | 2.1.1 | grpcweb-2.1.1-text | 通过 | — |
| c | soap | gSOAP | 2.8.124 | debian-baseline | 通过 | 通过 |
| c | soap | gSOAP | 2.8.146 | upstream-latest | 通过 | 通过 |
| cpp | soap | gSOAP | 2.8.124 | debian-baseline | 通过 | 通过 |
| cpp | soap | gSOAP | 2.8.146 | upstream-latest | 通过 | 通过 |
| python | soap | soapfish2 | 0.7.4 | soap11 | — | 通过 |
| python | soap | zeep | 3.4.0 | soap11 | 通过 | — |
| python | soap | zeep | 4.3.3 | soap11 | 通过 | — |
| python | thrift | Apache-Thrift | 0.13.0 | binary-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.13.0 | compact-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.13.0 | json-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.16.0 | binary-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.16.0 | compact-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.16.0 | json-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.25.0 | binary-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.25.0 | compact-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.25.0 | json-framed | 通过 | 通过 |

完整日志与 XML：[本轮归档](build/docker-runs)

## 脚本阶段

整轮结果：通过。

脚本错误：0；测试命令退出码：0；最终退出码：0。

校时诊断：[clock-state](<build/docker-runs/clock-state>)。
