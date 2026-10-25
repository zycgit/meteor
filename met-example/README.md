# Meteor 示例目录

## 介绍

本工程展示 Meteor 与各协议 SDK 的订单服务调用。Meteor 运行在 Java 端，其他语言使用对应的 SDK 或标准库。

提供者与消费者分别启动，各协议 Demo 提供直连和适用的注册发现用法。

Java 使用 JDK 17。

## 目录

- `→` 表示调用方向，`↔` 表示双向互通，具体启动步骤见各协议 Demo。
- Java 订单[接口](java/common/src/main/java/net/hasor/example/OrderDemoService.java)、[DTO](java/common/src/main/java/net/hasor/example/Order.java)和[实现](java/common/src/main/java/net/hasor/example/OrderDemoServiceImpl.java)。
- Java [注册中心接入与管理用法](../document/examples/multi-protocol-discovery.md#管理-api)。
- Go [共享订单业务](go/orders.go)。
- 协议契约 [order.proto](resource/order.proto)、[order.thrift](resource/order.thrift) 和 [order.x](resource/order.x)。

<a id="java"></a>

### Java

- **[RSF](java/meteor/src/main/java/net/hasor/example/rsf/README.md)**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/rsf/README.md#demo-如何启动)。
- **[Dubbo](java/meteor/src/main/java/net/hasor/example/dubbo/README.md)（Apache Dubbo）**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/dubbo/README.md#demo-如何启动)、[Meteor ↔ Apache Dubbo](java/meteor/src/main/java/net/hasor/example/dubbo/README.md#demo-如何启动)。
- **[Thrift](java/meteor/src/main/java/net/hasor/example/thrift/README.md)（Apache Thrift）**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/thrift/README.md#demo-如何启动)、[Meteor ↔ Apache Thrift](java/meteor/src/main/java/net/hasor/example/thrift/README.md#demo-如何启动)。
- **[Hprose](java/meteor/src/main/java/net/hasor/example/hprose/README.md)（Hprose Java SDK）**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/hprose/README.md#demo-如何启动)、[Meteor ↔ Hprose Java](java/meteor/src/main/java/net/hasor/example/hprose/README.md#demo-如何启动)。
- **[SOAP](java/meteor/src/main/java/net/hasor/example/soap/README.md)（Metro JAX-WS）**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/soap/README.md#demo-如何启动)、[Meteor ↔ Metro JAX-WS](java/meteor/src/main/java/net/hasor/example/soap/README.md#demo-如何启动)。
- **[JSON-RPC](java/meteor/src/main/java/net/hasor/example/jsonrpc/README.md)（jsonrpc4j）**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/jsonrpc/README.md#demo-如何启动)、[Meteor ↔ jsonrpc4j](java/meteor/src/main/java/net/hasor/example/jsonrpc/README.md#demo-如何启动)。
- **[XML-RPC](java/meteor/src/main/java/net/hasor/example/xmlrpc/README.md)（Apache XML-RPC）**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/xmlrpc/README.md#demo-如何启动)、[Meteor ↔ Apache XML-RPC](java/meteor/src/main/java/net/hasor/example/xmlrpc/README.md#demo-如何启动)。
- **[Hessian](java/meteor/src/main/java/net/hasor/example/hessian/README.md)（Caucho Hessian）**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/hessian/README.md#demo-如何启动)、[Meteor ↔ Caucho Hessian](java/meteor/src/main/java/net/hasor/example/hessian/README.md#demo-如何启动)。
- **[gRPC](java/meteor/src/main/java/net/hasor/example/grpc/README.md)（gRPC-Java）**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/grpc/README.md#demo-如何启动)、[Meteor ↔ gRPC-Java](java/meteor/src/main/java/net/hasor/example/grpc/README.md#demo-如何启动)。
- **[gRPC-Web](java/meteor/src/main/java/net/hasor/example/grpcweb/README.md)（Connect Kotlin/JVM）**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/grpcweb/README.md#demo-如何启动)、[Connect JVM（gRPC-Web） → Meteor](java/meteor/src/main/java/net/hasor/example/grpcweb/README.md#sdk-to-meteor)。
- **[Connect](java/meteor/src/main/java/net/hasor/example/connect/README.md)（Connect Kotlin/JVM、connect-rpc-java（社区））**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/connect/README.md#demo-如何启动)、[Connect JVM → Meteor](java/meteor/src/main/java/net/hasor/example/connect/README.md#sdk-to-meteor)、[Meteor → connect-rpc-java（社区）](java/meteor/src/main/java/net/hasor/example/connect/README.md#meteor-to-sdk)。
- **[Triple](java/meteor/src/main/java/net/hasor/example/triple/README.md)（Apache Dubbo Triple）**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/triple/README.md#demo-如何启动)、[Meteor ↔ Apache Dubbo](java/meteor/src/main/java/net/hasor/example/triple/README.md#demo-如何启动)。
- **[ONC RPC](java/meteor/src/main/java/net/hasor/example/oncrpc/README.md)（RemoteTea）**，例如：[Meteor ↔ Meteor](java/meteor/src/main/java/net/hasor/example/oncrpc/README.md#demo-如何启动)、[Meteor ↔ RemoteTea](java/meteor/src/main/java/net/hasor/example/oncrpc/README.md#demo-如何启动)。

<a id="python"></a>

### Python

- **[gRPC](python/grpc/README.md)（grpcio）**，例如：[Meteor ↔ grpcio](python/grpc/README.md#demo-如何启动)。
- **[Thrift](python/thrift/README.md)（Apache Thrift Python SDK）**，例如：[Meteor ↔ Apache Thrift](python/thrift/README.md#demo-如何启动)。
- **[SOAP](python/soap/README.md)（Zeep、soapfish2）**，例如：[Zeep → Meteor](python/soap/README.md#sdk-to-meteor)、[Meteor → soapfish2](python/soap/README.md#meteor-to-sdk)。
- **[JSON-RPC](python/jsonrpc/README.md)（Python 标准库）**，例如：[Meteor ↔ Python 标准库](python/jsonrpc/README.md#demo-如何启动)。
- **[XML-RPC](python/xmlrpc/README.md)（Python 标准库）**，例如：[Meteor ↔ Python xmlrpc](python/xmlrpc/README.md#demo-如何启动)。

<a id="node"></a>

### Node.js

- **[Hprose](node/hprose/README.md)（hprose SDK）**，例如：[Meteor ↔ hprose](node/hprose/README.md#demo-如何启动)。
- **[JSON-RPC](node/jsonrpc/README.md)（Node.js 标准库）**，例如：[Meteor ↔ Node.js 标准库](node/jsonrpc/README.md#demo-如何启动)。
- **[Connect](node/connect/README.md)（Connect SDK）**，例如：[Meteor ↔ Connect SDK](node/connect/README.md#demo-如何启动)。

<a id="go"></a>

### Go

- **[gRPC](go/grpc/README.md)（grpc-go）**，例如：[Meteor ↔ grpc-go](go/grpc/README.md#demo-如何启动)。
- **[Connect](go/connect/README.md)（connect-go）**，例如：[Meteor ↔ connect-go](go/connect/README.md#demo-如何启动)。
- **[Thrift](go/thrift/README.md)（Apache Thrift Go SDK）**，例如：[Meteor ↔ Apache Thrift](go/thrift/README.md#demo-如何启动)。

<a id="c"></a>

### C

- **[ONC RPC](c/oncrpc/README.md)（libtirpc）**，例如：[Meteor ↔ libtirpc](c/oncrpc/README.md#demo-如何启动)。

<a id="cpp"></a>

### C++

- **[ONC RPC](cpp/oncrpc/README.md)（libtirpc）**，例如：[Meteor ↔ libtirpc](cpp/oncrpc/README.md#demo-如何启动)。

<a id="browser"></a>

### 浏览器 JavaScript

- **[gRPC-Web](browser/grpcweb/README.md)（grpc-web SDK）**，例如：[grpc-web → Meteor](browser/grpcweb/README.md#demo-如何启动)。

<a id="shell"></a>

### Shell / curl

- **[JSON-RPC](shell/jsonrpc/README.md)（curl）**，例如：[curl → Meteor](shell/jsonrpc/README.md#demo-如何启动)。
