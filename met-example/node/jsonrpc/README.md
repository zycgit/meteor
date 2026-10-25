# Node.js JSON-RPC 示例

## 介绍

使用 Node.js 标准库 与 Java Meteor 互相调用订单服务。

## 特性

保存、查询订单；HTTP 与 JSON 调用，附件使用 Base64。

## 用法

需要 JDK 17、Node.js 22。命令在 Meteor 仓库根目录执行。服务地址固定为 `http://127.0.0.1:8080/jsonrpc`，订单 ID 为 `1001`。

## Demo 如何启动

### 直连

分别在两个终端运行提供者和消费者；切换方向前停止提供者。

<a id="sdk-to-meteor"></a>

#### fetch → Meteor

- 提供者：Meteor，[ProviderMain.java](../../java/meteor/src/main/java/net/hasor/example/jsonrpc/ProviderMain.java)。
- 消费者：fetch，[consumer.mjs](consumer.mjs)。

先在 IDEA 运行上述提供者的 `main`，再运行消费者：

```bash
node met-example/node/jsonrpc/consumer.mjs
```

<a id="meteor-to-sdk"></a>

#### Meteor → node:http

- 提供者：node:http，[provider.mjs](provider.mjs)。
- 消费者：Meteor，[ConsumerMain.java](../../java/meteor/src/main/java/net/hasor/example/jsonrpc/ConsumerMain.java)。

先启动提供者：

```bash
node met-example/node/jsonrpc/provider.mjs
```

随后在 IDEA 运行上述消费者的 `main`。

返回[示例总目录](../../README.md)。
