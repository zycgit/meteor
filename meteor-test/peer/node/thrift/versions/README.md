# Apache Thrift Node.js 版本对端

使用官方 SDK 的 Client、Processor、framed TCP 和 Binary / Compact / JSON 编码。
基础生成绑定覆盖 0.12.0、0.14.2、0.23.0、0.25.0；0.25.0 另提供官方 BigInt 生成绑定。

每个版本目录由测试准备阶段提供独立 `node_modules`，再运行：

```sh
node prepare.cjs /opt/meteor-peer/sdk-versions/thrift-0.25.0
```

生成后的目录包含 `node_modules`、`generated`，0.25.0 额外包含 `generated-bigint`。
入口核对实际安装版本，日志输出 `SDK Apache Thrift@<version>`。

```sh
# 提供者；监听就绪后输出 READY <port>。
node provider.cjs <profile> <host> <port> <binary|compact|json> <string|i64|bigint>

# 消费者；回显值及实际 JavaScript 类型校验成功后输出 OK。
node consumer.cjs <profile> <targetURL> <binary|compact|json> <string|i64|bigint> [value]
```

`echo(string)` 验证基础调用，`echoI64(i64)` 验证完整有符号 64 位整数。
`i64` 使用 SDK 的 `node-int64`，`bigint` 使用官方生成器的 `js:node,bigint` 绑定，
参数和返回值都必须为 JavaScript `bigint`。整数默认值为 `9223372036854775807`。

`generated` 与 `generated-bigint` 均为未修改的官方生成代码，来源见各自 `provenance.json`。
执行 `bash regenerate.sh` 可通过已有原生工具链镜像重新生成；中间产物写入 `meteor-test/build/`。
