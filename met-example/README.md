# Meteor 示例目录

## 介绍

本工程展示各语言发布和调用订单服务的用法，以及独立的管理 API 查询示例。Meteor 运行在 Java 端。

提供者与消费者分别启动，各协议 Demo 提供直连和适用的注册发现用法。

Java 使用 JDK 17，在 IDEA 中独立打开 `met-example` 并同步 Gradle，直接运行各 Demo 的 `main`。

各语言源码位于 `peer/<语言>`。Java 按实际 SDK 分目录，不同版本放在 SDK 的版本子目录；公共 Java 接口和 DTO 位于 `peer/java/contracts`，公共协议契约位于 `resource`。

## 目录

- [业务服务示例](#service-examples)：按语言和协议组织，包含直连、注册发现及 SDK 互通。
- [管理 API](#management-api)：通过 Meteor Java API 查询注册中心信息、实例和服务目录。
- [共享资源](#resources)：订单接口、业务实现和协议契约。

<a id="service-examples"></a>

## 业务服务示例

先选择语言和协议，再在对应 Demo 中选择提供者、消费者及接入方式。`→` 表示消费者调用提供者，`↔` 表示分别提供两个调用方向的示例。

<a id="java"></a>

### Java

- **[RSF](peer/java/meteor/src/main/java/net/hasor/example/rsf/README.md)**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/rsf/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/rsf/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/rsf/README.md#meteor-to-meteor-zookeeper)）。
- **[Dubbo](peer/java/meteor/src/main/java/net/hasor/example/dubbo/README.md)（Apache Dubbo）**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/dubbo/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/dubbo/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/dubbo/README.md#meteor-to-meteor-zookeeper)）、Apache Dubbo → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/dubbo/README.md#sdk-to-meteor)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/dubbo/README.md#sdk-registry-to-meteor)）、Meteor → Apache Dubbo（[直连](peer/java/meteor/src/main/java/net/hasor/example/dubbo/README.md#meteor-to-sdk)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/dubbo/README.md#meteor-registry-to-sdk)）。
- **[Thrift](peer/java/meteor/src/main/java/net/hasor/example/thrift/README.md)（Apache Thrift）**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/thrift/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/thrift/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/thrift/README.md#meteor-to-meteor-zookeeper)）、[Apache Thrift → Meteor](peer/java/meteor/src/main/java/net/hasor/example/thrift/README.md#sdk-to-meteor)、[Meteor → Apache Thrift](peer/java/meteor/src/main/java/net/hasor/example/thrift/README.md#meteor-to-sdk)。
- **[Hprose](peer/java/meteor/src/main/java/net/hasor/example/hprose/README.md)（Hprose Java SDK）**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/hprose/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/hprose/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/hprose/README.md#meteor-to-meteor-zookeeper)）、[Hprose Java → Meteor](peer/java/meteor/src/main/java/net/hasor/example/hprose/README.md#sdk-to-meteor)、[Meteor → Hprose Java](peer/java/meteor/src/main/java/net/hasor/example/hprose/README.md#meteor-to-sdk)。
- **[SOAP](peer/java/meteor/src/main/java/net/hasor/example/soap/README.md)（Metro JAX-WS）**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/soap/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/soap/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/soap/README.md#meteor-to-meteor-zookeeper)）、[Metro JAX-WS → Meteor](peer/java/meteor/src/main/java/net/hasor/example/soap/README.md#sdk-to-meteor)、[Meteor → Metro JAX-WS](peer/java/meteor/src/main/java/net/hasor/example/soap/README.md#meteor-to-sdk)。
- **[JSON-RPC](peer/java/meteor/src/main/java/net/hasor/example/jsonrpc/README.md)（jsonrpc4j）**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/jsonrpc/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/jsonrpc/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/jsonrpc/README.md#meteor-to-meteor-zookeeper)）、[jsonrpc4j → Meteor](peer/java/meteor/src/main/java/net/hasor/example/jsonrpc/README.md#sdk-to-meteor)、[Meteor → jsonrpc4j](peer/java/meteor/src/main/java/net/hasor/example/jsonrpc/README.md#meteor-to-sdk)。
- **[XML-RPC](peer/java/meteor/src/main/java/net/hasor/example/xmlrpc/README.md)（Apache XML-RPC）**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/xmlrpc/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/xmlrpc/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/xmlrpc/README.md#meteor-to-meteor-zookeeper)）、[Apache XML-RPC → Meteor](peer/java/meteor/src/main/java/net/hasor/example/xmlrpc/README.md#sdk-to-meteor)、[Meteor → Apache XML-RPC](peer/java/meteor/src/main/java/net/hasor/example/xmlrpc/README.md#meteor-to-sdk)。
- **[Hessian](peer/java/meteor/src/main/java/net/hasor/example/hessian/README.md)（Caucho Hessian）**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/hessian/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/hessian/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/hessian/README.md#meteor-to-meteor-zookeeper)）、[Caucho Hessian → Meteor](peer/java/meteor/src/main/java/net/hasor/example/hessian/README.md#sdk-to-meteor)、[Meteor → Caucho Hessian](peer/java/meteor/src/main/java/net/hasor/example/hessian/README.md#meteor-to-sdk)。
- **[gRPC](peer/java/meteor/src/main/java/net/hasor/example/grpc/README.md)（gRPC-Java）**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/grpc/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/grpc/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/grpc/README.md#meteor-to-meteor-zookeeper)）、[gRPC-Java → Meteor](peer/java/meteor/src/main/java/net/hasor/example/grpc/README.md#sdk-to-meteor)、[Meteor → gRPC-Java](peer/java/meteor/src/main/java/net/hasor/example/grpc/README.md#meteor-to-sdk)。
- **[gRPC-Web](peer/java/meteor/src/main/java/net/hasor/example/grpcweb/README.md)（Connect Kotlin/JVM）**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/grpcweb/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/grpcweb/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/grpcweb/README.md#meteor-to-meteor-zookeeper)）、[Connect Kotlin/JVM → Meteor](peer/java/meteor/src/main/java/net/hasor/example/grpcweb/README.md#sdk-to-meteor)。
- **[Connect](peer/java/meteor/src/main/java/net/hasor/example/connect/README.md)（Connect Kotlin/JVM、connect-rpc-java（社区））**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/connect/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/connect/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/connect/README.md#meteor-to-meteor-zookeeper)）、[Connect Kotlin/JVM → Meteor](peer/java/meteor/src/main/java/net/hasor/example/connect/README.md#sdk-to-meteor)、[Meteor → connect-rpc-java（社区）](peer/java/meteor/src/main/java/net/hasor/example/connect/README.md#meteor-to-sdk)。
- **[Triple](peer/java/meteor/src/main/java/net/hasor/example/triple/README.md)（Apache Dubbo Triple）**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/triple/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/triple/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/triple/README.md#meteor-to-meteor-zookeeper)）、[Apache Dubbo Triple → Meteor](peer/java/meteor/src/main/java/net/hasor/example/triple/README.md#sdk-to-meteor)、[Meteor → Apache Dubbo Triple](peer/java/meteor/src/main/java/net/hasor/example/triple/README.md#meteor-to-sdk)。
- **[ONC RPC](peer/java/meteor/src/main/java/net/hasor/example/oncrpc/README.md)（RemoteTea）**，例如：Meteor → Meteor（[直连](peer/java/meteor/src/main/java/net/hasor/example/oncrpc/README.md#meteor-to-meteor)、[Nacos](peer/java/meteor/src/main/java/net/hasor/example/oncrpc/README.md#meteor-to-meteor-nacos)、[ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/oncrpc/README.md#meteor-to-meteor-zookeeper)）、[RemoteTea → Meteor](peer/java/meteor/src/main/java/net/hasor/example/oncrpc/README.md#sdk-to-meteor)、[Meteor → RemoteTea](peer/java/meteor/src/main/java/net/hasor/example/oncrpc/README.md#meteor-to-sdk)。

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

<a id="management-api"></a>

## 管理 API

通过 Meteor Java API 查询注册中心信息、实例和服务目录。

- [Nacos](peer/java/meteor/src/main/java/net/hasor/example/NacosRegistryAdminMain.java)
- [ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/ZookeeperRegistryAdminMain.java)

<a id="resources"></a>

## 共享资源

- Java 订单[接口](peer/java/contracts/src/main/java/net/hasor/example/OrderDemoService.java)、[DTO](peer/java/contracts/src/main/java/net/hasor/example/Order.java)和[实现](peer/java/contracts/src/main/java/net/hasor/example/OrderDemoServiceImpl.java)。
- Go [共享订单业务](peer/go/orders.go)。
- 协议契约 [order.proto](resource/order.proto)、[order.thrift](resource/order.thrift) 和 [order.x](resource/order.x)。
