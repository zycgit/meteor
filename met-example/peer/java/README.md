# Java 示例

[返回示例目录](../../README.md)

## 运行准备

按[统一启动约定](../../README.md#run)导入工程，各入口在 IDEA 中运行 `main`。每组先启动提供者，再运行消费者，箭头表示消费者调用提供者。

- Meteor 入口位于 [meteor](meteor)，SDK 按实现和必要版本分目录，各自使用独立的运行依赖。
- 公共[订单接口](contracts/src/main/java/net/hasor/example/OrderDemoService.java)、[订单模型](contracts/src/main/java/net/hasor/example/Order.java)与 [Protobuf 消息](contracts/src/main/java/net/hasor/example/OrderContract.java)位于 `contracts`。
- 使用 Nacos 时先启动 `127.0.0.1:8848`，使用 ZooKeeper 时先启动 `127.0.0.1:2181`。地址或认证配置有变化时，同步修改所选入口的注册中心配置。
- 使用 Meteor 注册格式时，Nacos、ZooKeeper 提供者除业务端口 `8080` 外，还使用 `8081` 提供 RSF 管理调用；RSF 示例复用业务端口 `8080`。

协议：[RSF](#rsf) · [Dubbo](#dubbo) · [Thrift](#thrift) · [Hprose](#hprose) · [SOAP](#soap) · [JSON-RPC](#jsonrpc) · [XML-RPC](#xmlrpc) · [Hessian](#hessian) · [gRPC](#grpc) · [gRPC-Web](#grpcweb) · [Connect](#connect) · [Triple](#triple) · [ONC RPC](#oncrpc)。

## RSF

TCP 二进制通信，使用普通 Java 接口。业务地址为 `rsf://127.0.0.1:8080`。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/rsf/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/rsf/ConsumerMain.java)。
- **Meteor → Meteor（Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/rsf/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/rsf/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/rsf/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/rsf/ZookeeperRegistryConsumerMain.java)。

## Dubbo

SDK 位于 [apache-dubbo/3.3.6](apache-dubbo/3.3.6)，服务分组为 `default`、版本为 `1.0.0`。提供者监听 `0.0.0.0:8080`；直连消费者访问 `dubbo://127.0.0.1:8080`，注册发现消费者从注册中心取得业务地址。

Meteor 两端使用 Meteor 注册格式；[接入 Apache Dubbo 注册中心](#dubbo-registry)使用 Dubbo 注册格式，下面分别给出两种用法。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/dubbo/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/dubbo/ConsumerMain.java)。
- <a id="dubbo-direct-provider"></a>**Apache Dubbo → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/dubbo/ProviderMain.java)；消费者 [SdkConsumerMain](apache-dubbo/3.3.6/src/main/java/net/hasor/example/dubbo/SdkConsumerMain.java)。
- <a id="dubbo-direct-consumer"></a>**Meteor → Apache Dubbo（直连）**：提供者 [SdkProviderMain](apache-dubbo/3.3.6/src/main/java/net/hasor/example/dubbo/SdkProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/dubbo/ConsumerMain.java)。
- **Meteor → Meteor（Meteor 注册中心，Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/dubbo/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/dubbo/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（Meteor 注册中心，ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/dubbo/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/dubbo/ZookeeperRegistryConsumerMain.java)。

<a id="dubbo-registry"></a>

### 接入 Apache Dubbo 注册中心

以下两组演示 Meteor 直接发布、发现 Apache Dubbo 注册体系中的服务，以 ZooKeeper 的接口级注册发现为例。先启动 `127.0.0.1:2181`，双方使用接口 `net.hasor.example.OrderDemoService`、分组 `default`、版本 `1.0.0`。SDK 入口已配置 `register-mode=interface`。

两个 Meteor 入口均通过 `DubboRegistryConfiguration` 接入，配置随上下文构建并启动：

```java
DubboRegistryConfiguration registry = new DubboRegistryConfiguration()
        .setAddress("zookeeper://127.0.0.1:2181");
configuration.addExtension(registry);
```

<a id="dubbo-registry-consumer"></a>

#### Meteor → Apache Dubbo（Apache Dubbo 注册中心，ZooKeeper）

1. 运行 Apache Dubbo [SdkRegistryProviderMain](apache-dubbo/3.3.6/src/main/java/net/hasor/example/dubbo/SdkRegistryProviderMain.java)，发布订单服务并登记到注册中心。
2. 运行 Meteor [NativeRegistryConsumerMain](meteor/src/main/java/net/hasor/example/dubbo/NativeRegistryConsumerMain.java)，订阅同一接口，通过 `DubboRegistryClient.reference(binding)` 获取代理，等待发现提供者后保存并查询订单。

<a id="dubbo-registry-provider"></a>

#### Apache Dubbo → Meteor（Apache Dubbo 注册中心，ZooKeeper）

1. 运行 Meteor [NativeRegistryProviderMain](meteor/src/main/java/net/hasor/example/dubbo/NativeRegistryProviderMain.java)，设置 `publishHost=127.0.0.1` 并通过 `context.publisher().rsfService(...).register()` 发布服务，扩展自动按 Dubbo 格式登记业务地址。
2. 运行 Apache Dubbo [SdkRegistryConsumerMain](apache-dubbo/3.3.6/src/main/java/net/hasor/example/dubbo/SdkRegistryConsumerMain.java)，通过 `ReferenceConfig` 订阅并调用 Meteor 服务。

两组消费者均只配置注册中心地址，由发现结果确定提供者地址，成功后输出订单 `1001` 的 JSON。切换方向前停止上一组提供者，释放 `8080`。

## Thrift

Apache Thrift 使用 Binary 编码、Framed 传输和 `OrderThriftService` 服务名，业务地址为 `127.0.0.1:8080`。Meteor 使用普通 Java 接口与注解，SDK 使用 [order.thrift](../../resource/order.thrift) 的生成契约。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/thrift/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/thrift/ConsumerMain.java)。
- **Apache Thrift → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/thrift/ProviderMain.java)；消费者 [SdkConsumerMain](apache-thrift/src/main/java/net/hasor/example/thrift/SdkConsumerMain.java)。
- **Meteor → Apache Thrift（直连）**：提供者 [SdkProviderMain](apache-thrift/src/main/java/net/hasor/example/thrift/SdkProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/thrift/ConsumerMain.java)。
- **Meteor → Meteor（Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/thrift/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/thrift/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/thrift/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/thrift/ZookeeperRegistryConsumerMain.java)。

## Hprose

Hprose Java SDK 与 Meteor 共用普通 Java 接口和 DTO，函数别名前缀为 `orders`。业务地址为 `http://127.0.0.1:8080/hprose`。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/hprose/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/hprose/ConsumerMain.java)。
- **Hprose Java → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/hprose/ProviderMain.java)；消费者 [SdkConsumerMain](hprose-java/src/main/java/net/hasor/example/hprose/SdkConsumerMain.java)。
- **Meteor → Hprose Java（直连）**：提供者 [SdkProviderMain](hprose-java/src/main/java/net/hasor/example/hprose/SdkProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/hprose/ConsumerMain.java)。
- **Meteor → Meteor（Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/hprose/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/hprose/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/hprose/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/hprose/ZookeeperRegistryConsumerMain.java)。

## SOAP

Metro JAX-WS 与 Meteor 通过 HTTP 调用 SOAP 服务。业务地址为 `http://127.0.0.1:8080/soap/orders`，Meteor 自动生成的 WSDL 位于该地址的 `?wsdl`。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/soap/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/soap/ConsumerMain.java)。
- **Metro JAX-WS → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/soap/ProviderMain.java)；消费者 [SdkConsumerMain](metro-jaxws/src/main/java/net/hasor/example/soap/SdkConsumerMain.java)。
- **Meteor → Metro JAX-WS（直连）**：提供者 [SdkProviderMain](metro-jaxws/src/main/java/net/hasor/example/soap/SdkProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/soap/ConsumerMain.java)。
- **Meteor → Meteor（Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/soap/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/soap/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/soap/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/soap/ZookeeperRegistryConsumerMain.java)。

<a id="jsonrpc"></a>

## JSON-RPC

jsonrpc4j 与 Meteor 共用普通 Java 接口和 DTO，服务别名为 `orders`。业务地址为 `http://127.0.0.1:8080/jsonrpc`。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/jsonrpc/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/jsonrpc/ConsumerMain.java)。
- **jsonrpc4j → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/jsonrpc/ProviderMain.java)；消费者 [SdkConsumerMain](jsonrpc4j/src/main/java/net/hasor/example/jsonrpc/SdkConsumerMain.java)。
- **Meteor → jsonrpc4j（直连）**：提供者 [SdkProviderMain](jsonrpc4j/src/main/java/net/hasor/example/jsonrpc/SdkProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/jsonrpc/ConsumerMain.java)。
- **Meteor → Meteor（Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/jsonrpc/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/jsonrpc/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/jsonrpc/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/jsonrpc/ZookeeperRegistryConsumerMain.java)。

<a id="xmlrpc"></a>

## XML-RPC

Apache XML-RPC 使用 struct 表示订单、base64 表示附件，服务名为 `orders`。业务地址为 `http://127.0.0.1:8080/xmlrpc`。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/xmlrpc/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/xmlrpc/ConsumerMain.java)。
- **Apache XML-RPC → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/xmlrpc/ProviderMain.java)；消费者 [SdkConsumerMain](apache-xmlrpc/src/main/java/net/hasor/example/xmlrpc/SdkConsumerMain.java)。
- **Meteor → Apache XML-RPC（直连）**：提供者 [SdkProviderMain](apache-xmlrpc/src/main/java/net/hasor/example/xmlrpc/SdkProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/xmlrpc/ConsumerMain.java)。
- **Meteor → Meteor（Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/xmlrpc/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/xmlrpc/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/xmlrpc/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/xmlrpc/ZookeeperRegistryConsumerMain.java)。

## Hessian

Caucho Hessian 与 Meteor 共用普通 Java 接口和 DTO。业务地址为 `http://127.0.0.1:8080/hessian`。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/hessian/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/hessian/ConsumerMain.java)。
- **Caucho Hessian → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/hessian/ProviderMain.java)；消费者 [SdkConsumerMain](caucho-hessian/src/main/java/net/hasor/example/hessian/SdkConsumerMain.java)。
- **Meteor → Caucho Hessian（直连）**：提供者 [SdkProviderMain](caucho-hessian/src/main/java/net/hasor/example/hessian/SdkProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/hessian/ConsumerMain.java)。
- **Meteor → Meteor（Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/hessian/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/hessian/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/hessian/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/hessian/ZookeeperRegistryConsumerMain.java)。

## gRPC

gRPC-Java 使用 HTTP/2 和 Protobuf，与 Meteor 共用 [order.proto](../../resource/order.proto)。本地端口为 `8080`，挂载路径为 `/grpc`。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/grpc/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/grpc/ConsumerMain.java)。
- **gRPC-Java → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/grpc/ProviderMain.java)；消费者 [SdkConsumerMain](grpc-java/src/main/java/net/hasor/example/grpc/SdkConsumerMain.java)。
- **Meteor → gRPC-Java（直连）**：提供者 [SdkProviderMain](grpc-java/src/main/java/net/hasor/example/grpc/SdkProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/grpc/ConsumerMain.java)。
- **Meteor → Meteor（Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/grpc/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/grpc/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/grpc/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/grpc/ZookeeperRegistryConsumerMain.java)。

<a id="grpcweb"></a>

## gRPC-Web

使用 Protobuf 和 [order.proto](../../resource/order.proto)，业务地址为 `http://127.0.0.1:8080/grpc-web`。SDK 消费者使用 Connect Kotlin/JVM 的 `GRPC_WEB` 模式；另有[浏览器示例](../browser/README.md#grpcweb)。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/grpcweb/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/grpcweb/ConsumerMain.java)。
- **Connect Kotlin/JVM（gRPC-Web） → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/grpcweb/ProviderMain.java)；消费者 [SdkConsumerMain](connect-kotlin/src/main/java/net/hasor/example/grpcweb/SdkConsumerMain.java)。
- **Meteor → Meteor（Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/grpcweb/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/grpcweb/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/grpcweb/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/grpcweb/ZookeeperRegistryConsumerMain.java)。

## Connect

使用 Protobuf 和 [order.proto](../../resource/order.proto)，业务地址为 `http://127.0.0.1:8080/connect`。SDK 消费者使用官方 Connect Kotlin/JVM，SDK 提供者使用社区 connect-rpc-java。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/connect/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/connect/ConsumerMain.java)。
- **Connect Kotlin/JVM → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/connect/ProviderMain.java)；消费者 [SdkConsumerMain](connect-kotlin/src/main/java/net/hasor/example/connect/SdkConsumerMain.java)。
- **Meteor → connect-rpc-java（社区）（直连）**：提供者 [SdkProviderMain](connect-rpc-java/src/main/java/net/hasor/example/connect/SdkProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/connect/ConsumerMain.java)。
- **Meteor → Meteor（Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/connect/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/connect/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/connect/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/connect/ZookeeperRegistryConsumerMain.java)。

## Triple

SDK 位于 [apache-dubbo/3.2.20](apache-dubbo/3.2.20)，使用 HTTP/2、Protobuf 和 [order.proto](../../resource/order.proto)。本地端口为 `8080`、挂载根路径为 `/`，服务名为 `meteor.example.OrderDemoService`，SDK 使用对应的[契约接口](apache-dubbo/3.2.20/src/main/java/meteor/example/OrderDemoService.java)。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/triple/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/triple/ConsumerMain.java)。
- **Apache Dubbo → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/triple/ProviderMain.java)；消费者 [SdkConsumerMain](apache-dubbo/3.2.20/src/main/java/net/hasor/example/triple/SdkConsumerMain.java)。
- **Meteor → Apache Dubbo（直连）**：提供者 [SdkProviderMain](apache-dubbo/3.2.20/src/main/java/net/hasor/example/triple/SdkProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/triple/ConsumerMain.java)。
- **Meteor → Meteor（Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/triple/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/triple/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/triple/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/triple/ZookeeperRegistryConsumerMain.java)。

<a id="oncrpc"></a>

## ONC RPC

RemoteTea 使用 TCP/XDR 和 [order.x](../../resource/order.x)，业务地址为 `127.0.0.1:8080`，无需 rpcbind。程序号为 `0x31234569`、版本为 `1`，`save`、`find` 的过程号分别为 `1`、`2`。

Meteor 使用 [OncRpcOrderService](meteor/src/main/java/net/hasor/example/oncrpc/OncRpcOrderService.java)，RemoteTea 使用 [SdkOrderValue](remotetea/src/main/java/net/hasor/example/oncrpc/SdkOrderValue.java) 映射订单。

- **Meteor → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/oncrpc/ProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/oncrpc/ConsumerMain.java)。
- **RemoteTea → Meteor（直连）**：提供者 [ProviderMain](meteor/src/main/java/net/hasor/example/oncrpc/ProviderMain.java)；消费者 [SdkConsumerMain](remotetea/src/main/java/net/hasor/example/oncrpc/SdkConsumerMain.java)。
- **Meteor → RemoteTea（直连）**：提供者 [SdkProviderMain](remotetea/src/main/java/net/hasor/example/oncrpc/SdkProviderMain.java)；消费者 [ConsumerMain](meteor/src/main/java/net/hasor/example/oncrpc/ConsumerMain.java)。
- **Meteor → Meteor（Nacos）**：提供者 [NacosRegistryProviderMain](meteor/src/main/java/net/hasor/example/oncrpc/NacosRegistryProviderMain.java)；消费者 [NacosRegistryConsumerMain](meteor/src/main/java/net/hasor/example/oncrpc/NacosRegistryConsumerMain.java)。
- **Meteor → Meteor（ZooKeeper）**：提供者 [ZookeeperRegistryProviderMain](meteor/src/main/java/net/hasor/example/oncrpc/ZookeeperRegistryProviderMain.java)；消费者 [ZookeeperRegistryConsumerMain](meteor/src/main/java/net/hasor/example/oncrpc/ZookeeperRegistryConsumerMain.java)。
