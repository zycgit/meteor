# Go 与 Meteor Thrift 示例

## 介绍

Go 使用 Apache Thrift（Go），与 Java Meteor 互相保存和查询订单。

## 特性

- binary 编码、framed TCP 传输。
- 共用 [order.thrift](../../resource/order.thrift)，订单包含编号、描述和二进制附件。

## 用法

固定地址 `thrift://127.0.0.1:8080`，消费者保存并查询订单 `1001`，输出 `saved`、`found` JSON。

## Demo 如何启动

准备 JDK 17、Go 1.26 或更新版本。以下命令从仓库根目录执行，双方各用一个终端；切换方向前停止原提供者。

### 直连

<a id="sdk-to-meteor"></a>

#### Apache Thrift（Go） → Meteor

- 提供者：Meteor [ProviderMain](../../java/meteor/src/main/java/net/hasor/example/thrift/ProviderMain.java)。
- 消费者：Apache Thrift（Go） [consumer/main.go](consumer/main.go)。

先在 IDEA 运行上述提供者的 `main`，再运行消费者：

```bash
cd met-example/go
go run ./thrift/consumer
```

<a id="meteor-to-sdk"></a>

#### Meteor → Apache Thrift（Go）

- 提供者：Apache Thrift（Go） [provider/main.go](provider/main.go)。
- 消费者：Meteor [ConsumerMain](../../java/meteor/src/main/java/net/hasor/example/thrift/ConsumerMain.java)。

先启动提供者：

```bash
cd met-example/go
go run ./thrift/provider
```

随后在 IDEA 运行上述消费者的 `main`。
