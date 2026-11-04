# Meteor RPC 测试报告

结果：通过。

skipError：`true`。

用例筛选：全部。

通过 8154 项，失败 0 项，跳过 0 项，已确认未执行 0 项，结果未知 0 项。

详细结果：[summary.json](build/docker-runs/meteor-test/build/interoperability/summary.json)

## 标准矩阵

执行前计划 398 项；通过 398，失败 0，准备失败 0，清理失败 0，跳过 0，未执行 0，结果未知 0。

本节各状态互斥；准备失败与清理失败单列，顶部通过和失败数来自原始 JUnit 结果。

其它既有场景按运行期发现统计，不计入执行前精确计划。

- RPC `connect`，版本 `1`：计划 35，通过 35。
- RPC `dubbo`：计划 10，通过 10。
- RPC `dubbo`，版本 `2`：计划 8，通过 8。
- RPC `dubbo`，版本 `2/3`：计划 4，通过 4。
- RPC `dubbo`，版本 `3`：计划 3，通过 3。
- RPC `grpc-web`：计划 18，通过 18。
- RPC `grpc`：计划 49，通过 49。
- RPC `hessian`，版本 `2`：计划 12，通过 12。
- RPC `hprose`：计划 9，通过 9。
- RPC `jsonrpc`，版本 `2.0`：计划 19，通过 19。
- RPC `oncrpc`，版本 `2`：计划 36，通过 36。
- RPC `rsf`，版本 `1`：计划 31，通过 31。
- RPC `soap`，版本 `1.1`：计划 25，通过 25。
- RPC `thrift`：计划 121，通过 121。
- RPC `triple`：计划 9，通过 9。
- RPC `xmlrpc`：计划 9，通过 9。

- 提供者 `c` → 消费者 `c`：计划 4，通过 4。
- 提供者 `c` → 消费者 `cpp`：计划 4，通过 4。
- 提供者 `c` → 消费者 `java`：计划 4，通过 4。
- 提供者 `cpp` → 消费者 `c`：计划 4，通过 4。
- 提供者 `cpp` → 消费者 `cpp`：计划 12，通过 12。
- 提供者 `cpp` → 消费者 `go`：计划 4，通过 4。
- 提供者 `cpp` → 消费者 `java`：计划 20，通过 20。
- 提供者 `cpp` → 消费者 `node`：计划 4，通过 4。
- 提供者 `cpp` → 消费者 `python`：计划 4，通过 4。
- 提供者 `go` → 消费者 `cpp`：计划 4，通过 4。
- 提供者 `go` → 消费者 `go`：计划 3，通过 3。
- 提供者 `go` → 消费者 `java`：计划 12，通过 12。
- 提供者 `go` → 消费者 `node`：计划 3，通过 3。
- 提供者 `go` → 消费者 `python`：计划 3，通过 3。
- 提供者 `java` → 消费者 `c`：计划 4，通过 4。
- 提供者 `java` → 消费者 `cpp`：计划 20，通过 20。
- 提供者 `java` → 消费者 `go`：计划 10，通过 10。
- 提供者 `java` → 消费者 `java`：计划 164，通过 164。
- 提供者 `java` → 消费者 `node`：计划 15，通过 15。
- 提供者 `java` → 消费者 `python`：计划 19，通过 19。
- 提供者 `node` → 消费者 `cpp`：计划 4，通过 4。
- 提供者 `node` → 消费者 `go`：计划 3，通过 3。
- 提供者 `node` → 消费者 `java`：计划 20，通过 20。
- 提供者 `node` → 消费者 `node`：计划 6，通过 6。
- 提供者 `node` → 消费者 `python`：计划 5，通过 5。
- 提供者 `python` → 消费者 `cpp`：计划 4，通过 4。
- 提供者 `python` → 消费者 `go`：计划 3，通过 3。
- 提供者 `python` → 消费者 `java`：计划 24，通过 24。
- 提供者 `python` → 消费者 `node`：计划 5，通过 5。
- 提供者 `python` → 消费者 `python`：计划 7，通过 7。

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
| cpp | jsonrpc | libjson-rpc-cpp | 0.7.0 | legacy-debian | 通过 | 通过 |
| cpp | jsonrpc | libjson-rpc-cpp | 1.4.1 | upstream-latest | 通过 | 通过 |
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
