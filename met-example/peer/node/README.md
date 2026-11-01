# Node.js 示例

[返回示例目录](../../README.md)

## 运行准备

使用 Node.js 22 或更新版本。按[统一启动约定](../../README.md#run)准备 Java 端，以下命令从 Meteor 仓库根目录执行。

Hprose、Connect 共用依赖，安装一次即可；JSON-RPC 只使用标准库：

```bash
npm --prefix met-example/peer/node ci
```

消费者保存并查询订单 `1001`，输出订单结果。

协议：[Hprose](#hprose) · [JSON-RPC](#jsonrpc) · [Connect](#connect)。

## Hprose

使用 hprose SDK 和二进制编码，附件使用 `Buffer`。地址为 `http://127.0.0.1:8080/hprose`。

### hprose → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain.java](../java/meteor/src/main/java/net/hasor/example/hprose/ProviderMain.java)，再执行消费者 [consumer.mjs](hprose/consumer.mjs)：

```bash
node met-example/peer/node/hprose/consumer.mjs
```

### Meteor → hprose（直连）

先启动提供者 [provider.mjs](hprose/provider.mjs)：

```bash
node met-example/peer/node/hprose/provider.mjs
```

随后在 IDEA 运行 Meteor [ConsumerMain.java](../java/meteor/src/main/java/net/hasor/example/hprose/ConsumerMain.java)。

<a id="jsonrpc"></a>

## JSON-RPC

使用 `fetch`、`node:http` 实现 HTTP/JSON 调用，附件使用 Base64。地址为 `http://127.0.0.1:8080/jsonrpc`。

### fetch → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain.java](../java/meteor/src/main/java/net/hasor/example/jsonrpc/ProviderMain.java)，再执行消费者 [consumer.mjs](jsonrpc/consumer.mjs)：

```bash
node met-example/peer/node/jsonrpc/consumer.mjs
```

### Meteor → node:http（直连）

先启动提供者 [provider.mjs](jsonrpc/provider.mjs)：

```bash
node met-example/peer/node/jsonrpc/provider.mjs
```

随后在 IDEA 运行 Meteor [ConsumerMain.java](../java/meteor/src/main/java/net/hasor/example/jsonrpc/ConsumerMain.java)。

## Connect

使用 @connectrpc/connect、@connectrpc/connect-node，通过 HTTP/1.1 和 Protobuf 调用。地址为 `http://127.0.0.1:8080/connect`，已包含[契约代码](connect/generated/order_pb.js)。

### @connectrpc/connect → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain.java](../java/meteor/src/main/java/net/hasor/example/connect/ProviderMain.java)，再执行消费者 [consumer.mjs](connect/consumer.mjs)：

```bash
node met-example/peer/node/connect/consumer.mjs
```

### Meteor → @connectrpc/connect-node（直连）

先启动提供者 [provider.mjs](connect/provider.mjs)：

```bash
node met-example/peer/node/connect/provider.mjs
```

随后在 IDEA 运行 Meteor [ConsumerMain.java](../java/meteor/src/main/java/net/hasor/example/connect/ConsumerMain.java)。
