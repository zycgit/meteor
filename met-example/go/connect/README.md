# Go 与 Meteor Connect 示例

## 介绍

Go 使用 connect-go，与 Java Meteor 互相保存和查询订单。

## 特性

- HTTP/1.1、Protobuf 编码。
- 共用 [order.proto](../../resource/order.proto)，订单包含编号、描述和二进制附件。

## 用法

固定地址 `http://127.0.0.1:8080/connect`，消费者保存并查询订单 `1001`，输出 `saved`、`found` JSON。

## Demo 如何启动

准备 JDK 17、Go 1.26 或更新版本。以下命令从仓库根目录执行，双方各用一个终端；切换方向前停止原提供者。

### 直连

<a id="sdk-to-meteor"></a>

#### connect-go → Meteor

- 提供者：Meteor [ProviderMain](../../java/meteor/src/main/java/net/hasor/example/connect/ProviderMain.java)。
- 消费者：connect-go [consumer/main.go](consumer/main.go)。

先在 IDEA 运行上述提供者的 `main`，再运行消费者：

```bash
cd met-example/go
go run ./connect/consumer
```

<a id="meteor-to-sdk"></a>

#### Meteor → connect-go

- 提供者：connect-go [provider/main.go](provider/main.go)。
- 消费者：Meteor [ConsumerMain](../../java/meteor/src/main/java/net/hasor/example/connect/ConsumerMain.java)。

先启动提供者：

```bash
cd met-example/go
go run ./connect/provider
```

随后在 IDEA 运行上述消费者的 `main`。
