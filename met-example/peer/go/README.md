# Go 示例

[返回示例目录](../../README.md)

## 运行准备

使用 Go 1.26 或更新版本。按[统一启动约定](../../README.md#run)准备 Java 端，每个终端先从 Meteor 仓库根目录进入 Go 模块，再执行下面的命令：

```bash
cd met-example/peer/go
```

各协议共用 [go.mod](go.mod) 和[订单业务](orders.go)。消费者保存并查询订单 `1001`，输出 `saved`、`found` JSON。

协议：[gRPC](#grpc) · [Connect](#connect) · [Thrift](#thrift)。

## gRPC

grpc-go 使用 HTTP/2、Protobuf 和 [order.proto](../../resource/order.proto)。地址为 `grpc://127.0.0.1:8080/grpc`。

### grpc-go → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain](../java/meteor/src/main/java/net/hasor/example/grpc/ProviderMain.java)，再执行消费者 [consumer/main.go](grpc/consumer/main.go)：

```bash
go run ./grpc/consumer
```

### Meteor → grpc-go（直连）

先启动提供者 [provider/main.go](grpc/provider/main.go)：

```bash
go run ./grpc/provider
```

随后在 IDEA 运行 Meteor [ConsumerMain](../java/meteor/src/main/java/net/hasor/example/grpc/ConsumerMain.java)。

## Connect

connect-go 使用 HTTP/1.1、Protobuf 和 [order.proto](../../resource/order.proto)。地址为 `http://127.0.0.1:8080/connect`。

### connect-go → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain](../java/meteor/src/main/java/net/hasor/example/connect/ProviderMain.java)，再执行消费者 [consumer/main.go](connect/consumer/main.go)：

```bash
go run ./connect/consumer
```

### Meteor → connect-go（直连）

先启动提供者 [provider/main.go](connect/provider/main.go)：

```bash
go run ./connect/provider
```

随后在 IDEA 运行 Meteor [ConsumerMain](../java/meteor/src/main/java/net/hasor/example/connect/ConsumerMain.java)。

## Thrift

Apache Thrift 使用 Binary 编码、Framed TCP 传输和 [order.thrift](../../resource/order.thrift)。地址为 `thrift://127.0.0.1:8080`。

### Apache Thrift（Go） → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain](../java/meteor/src/main/java/net/hasor/example/thrift/ProviderMain.java)，再执行消费者 [consumer/main.go](thrift/consumer/main.go)：

```bash
go run ./thrift/consumer
```

### Meteor → Apache Thrift（Go）（直连）

先启动提供者 [provider/main.go](thrift/provider/main.go)：

```bash
go run ./thrift/provider
```

随后在 IDEA 运行 Meteor [ConsumerMain](../java/meteor/src/main/java/net/hasor/example/thrift/ConsumerMain.java)。
