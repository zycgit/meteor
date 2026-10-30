# Meteor 示例目录

## 介绍

本工程展示 Meteor 与各协议 SDK 的订单服务调用。Meteor 运行在 Java 端，其他语言使用对应的 SDK 或标准库。

提供者与消费者分别启动，各协议 Demo 提供直连和适用的注册发现用法。

Java 使用 JDK 17，在 IDEA 中独立打开 `met-example` 并同步 Gradle，直接运行各 Demo 的 `main`。

各语言源码位于 `peer/<语言>`。Java 按实际 SDK 分目录，不同版本放在 SDK 的版本子目录；公共 Java 接口和 DTO 位于 `peer/java/contracts`，公共协议契约位于 `resource`。

## 目录

- `→` 表示调用方向，`↔` 表示双向互通，具体启动步骤见各协议 Demo。
- Java 订单[接口](peer/java/contracts/src/main/java/net/hasor/example/OrderDemoService.java)、[DTO](peer/java/contracts/src/main/java/net/hasor/example/Order.java)和[实现](peer/java/contracts/src/main/java/net/hasor/example/OrderDemoServiceImpl.java)。
- Java [注册中心接入与管理用法](../document/examples/multi-protocol-discovery.md#管理-api)。
- Go [共享订单业务](peer/go/orders.go)。
- 协议契约 [order.proto](resource/order.proto)、[order.thrift](resource/order.thrift) 和 [order.x](resource/order.x)。

<a id="java"></a>

### Java

- **[RSF](peer/java/meteor/src/main/java/net/hasor/example/rsf/README.md)**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/rsf/README.md#demo-如何启动)。
- **[Dubbo](peer/java/meteor/src/main/java/net/hasor/example/dubbo/README.md)（Apache Dubbo）**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/dubbo/README.md#demo-如何启动)、[Meteor ↔ Apache Dubbo](peer/java/meteor/src/main/java/net/hasor/example/dubbo/README.md#demo-如何启动)。
- **[Thrift](peer/java/meteor/src/main/java/net/hasor/example/thrift/README.md)（Apache Thrift）**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/thrift/README.md#demo-如何启动)、[Meteor ↔ Apache Thrift](peer/java/meteor/src/main/java/net/hasor/example/thrift/README.md#demo-如何启动)。
- **[Hprose](peer/java/meteor/src/main/java/net/hasor/example/hprose/README.md)（Hprose Java SDK）**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/hprose/README.md#demo-如何启动)、[Meteor ↔ Hprose Java](peer/java/meteor/src/main/java/net/hasor/example/hprose/README.md#demo-如何启动)。
- **[SOAP](peer/java/meteor/src/main/java/net/hasor/example/soap/README.md)（Metro JAX-WS）**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/soap/README.md#demo-如何启动)、[Meteor ↔ Metro JAX-WS](peer/java/meteor/src/main/java/net/hasor/example/soap/README.md#demo-如何启动)。
- **[JSON-RPC](peer/java/meteor/src/main/java/net/hasor/example/jsonrpc/README.md)（jsonrpc4j）**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/jsonrpc/README.md#demo-如何启动)、[Meteor ↔ jsonrpc4j](peer/java/meteor/src/main/java/net/hasor/example/jsonrpc/README.md#demo-如何启动)。
- **[XML-RPC](peer/java/meteor/src/main/java/net/hasor/example/xmlrpc/README.md)（Apache XML-RPC）**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/xmlrpc/README.md#demo-如何启动)、[Meteor ↔ Apache XML-RPC](peer/java/meteor/src/main/java/net/hasor/example/xmlrpc/README.md#demo-如何启动)。
- **[Hessian](peer/java/meteor/src/main/java/net/hasor/example/hessian/README.md)（Caucho Hessian）**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/hessian/README.md#demo-如何启动)、[Meteor ↔ Caucho Hessian](peer/java/meteor/src/main/java/net/hasor/example/hessian/README.md#demo-如何启动)。
- **[gRPC](peer/java/meteor/src/main/java/net/hasor/example/grpc/README.md)（gRPC-Java）**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/grpc/README.md#demo-如何启动)、[Meteor ↔ gRPC-Java](peer/java/meteor/src/main/java/net/hasor/example/grpc/README.md#demo-如何启动)。
- **[gRPC-Web](peer/java/meteor/src/main/java/net/hasor/example/grpcweb/README.md)（Connect Kotlin/JVM）**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/grpcweb/README.md#demo-如何启动)、[Connect JVM（gRPC-Web） → Meteor](peer/java/meteor/src/main/java/net/hasor/example/grpcweb/README.md#sdk-to-meteor)。
- **[Connect](peer/java/meteor/src/main/java/net/hasor/example/connect/README.md)（Connect Kotlin/JVM、connect-rpc-java（社区））**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/connect/README.md#demo-如何启动)、[Connect JVM → Meteor](peer/java/meteor/src/main/java/net/hasor/example/connect/README.md#sdk-to-meteor)、[Meteor → connect-rpc-java（社区）](peer/java/meteor/src/main/java/net/hasor/example/connect/README.md#meteor-to-sdk)。
- **[Triple](peer/java/meteor/src/main/java/net/hasor/example/triple/README.md)（Apache Dubbo Triple）**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/triple/README.md#demo-如何启动)、[Meteor ↔ Apache Dubbo](peer/java/meteor/src/main/java/net/hasor/example/triple/README.md#demo-如何启动)。
- **[ONC RPC](peer/java/meteor/src/main/java/net/hasor/example/oncrpc/README.md)（RemoteTea）**，例如：[Meteor ↔ Meteor](peer/java/meteor/src/main/java/net/hasor/example/oncrpc/README.md#demo-如何启动)、[Meteor ↔ RemoteTea](peer/java/meteor/src/main/java/net/hasor/example/oncrpc/README.md#demo-如何启动)。

<a id="python"></a>

### Python

- **[gRPC](peer/python/grpc/README.md)（grpcio）**，例如：[Meteor ↔ grpcio](peer/python/grpc/README.md#demo-如何启动)。
- **[Thrift](peer/python/thrift/README.md)（Apache Thrift Python SDK）**，例如：[Meteor ↔ Apache Thrift](peer/python/thrift/README.md#demo-如何启动)。
- **[SOAP](peer/python/soap/README.md)（Zeep、soapfish2）**，例如：[Zeep → Meteor](peer/python/soap/README.md#sdk-to-meteor)、[Meteor → soapfish2](peer/python/soap/README.md#meteor-to-sdk)。
- **[JSON-RPC](peer/python/jsonrpc/README.md)（Python 标准库）**，例如：[Meteor ↔ Python 标准库](peer/python/jsonrpc/README.md#demo-如何启动)。
- **[XML-RPC](peer/python/xmlrpc/README.md)（Python 标准库）**，例如：[Meteor ↔ Python xmlrpc](peer/python/xmlrpc/README.md#demo-如何启动)。

<a id="node"></a>

### Node.js

- **[Hprose](peer/node/hprose/README.md)（hprose SDK）**，例如：[Meteor ↔ hprose](peer/node/hprose/README.md#demo-如何启动)。
- **[JSON-RPC](peer/node/jsonrpc/README.md)（Node.js 标准库）**，例如：[Meteor ↔ Node.js 标准库](peer/node/jsonrpc/README.md#demo-如何启动)。
- **[Connect](peer/node/connect/README.md)（Connect SDK）**，例如：[Meteor ↔ Connect SDK](peer/node/connect/README.md#demo-如何启动)。

<a id="go"></a>

### Go

- **[gRPC](peer/go/grpc/README.md)（grpc-go）**，例如：[Meteor ↔ grpc-go](peer/go/grpc/README.md#demo-如何启动)。
- **[Connect](peer/go/connect/README.md)（connect-go）**，例如：[Meteor ↔ connect-go](peer/go/connect/README.md#demo-如何启动)。
- **[Thrift](peer/go/thrift/README.md)（Apache Thrift Go SDK）**，例如：[Meteor ↔ Apache Thrift](peer/go/thrift/README.md#demo-如何启动)。

<a id="c"></a>

### C

- **[ONC RPC](peer/c/oncrpc/README.md)（libtirpc）**，例如：[Meteor ↔ libtirpc](peer/c/oncrpc/README.md#demo-如何启动)。

<a id="cpp"></a>

### C++

- **[ONC RPC](peer/cpp/oncrpc/README.md)（libtirpc）**，例如：[Meteor ↔ libtirpc](peer/cpp/oncrpc/README.md#demo-如何启动)。

<a id="browser"></a>

### 浏览器 JavaScript

- **[gRPC-Web](peer/browser/grpcweb/README.md)（grpc-web SDK）**，例如：[grpc-web → Meteor](peer/browser/grpcweb/README.md#demo-如何启动)。

<a id="shell"></a>

### Shell / curl

- **[JSON-RPC](peer/shell/jsonrpc/README.md)（curl）**，例如：[curl → Meteor](peer/shell/jsonrpc/README.md#demo-如何启动)。
