# Meteor 示例目录

本工程演示各语言发布和调用订单服务的用法。Meteor 运行在 Java 端，协议互通示例按 `peer/<语言>` 组织，框架整合示例位于 `integration/java`。

- [运行约定](#run)
- [业务服务示例](#service-examples)
- [框架整合](#framework-integration)
- [管理 API](#management-api)
- [共享资源](#resources)

<a id="run"></a>

## 运行约定

1. 准备 JDK 17，在 IDEA 中独立打开 `met-example` 并同步 Gradle。工程通过 `includeBuild('..')` 引用当前 Meteor 源码，Java 入口直接运行各自模块的 `main`。
2. 按语言 README 准备对应工具链。除另有说明，命令从 Meteor 仓库根目录执行。
3. 每次选择一组示例，先启动提供者，再在另一进程运行消费者。协议互通示例默认共用本地业务端口 `8080`，切换前停止原提供者。

消费者保存并查询订单 `1001`，结果包含编号、描述和附件。`→` 表示消费者调用提供者，`↔` 表示分别提供两个调用方向的示例。

<a id="service-examples"></a>

## 业务服务示例

### Java

[Java 运行准备与协议入口](peer/java/README.md)。以下协议的 Meteor → Meteor 示例均包含直连、Nacos、ZooKeeper；SDK 互通方向见各项说明。

- [RSF](peer/java/README.md#rsf)：Meteor → Meteor。
- [Dubbo](peer/java/README.md#dubbo)：Apache Dubbo ↔ Meteor（直连、[注册中心](peer/java/README.md#dubbo-registry)）。
- [Thrift](peer/java/README.md#thrift)：Apache Thrift ↔ Meteor。
- [Hprose](peer/java/README.md#hprose)：Hprose Java ↔ Meteor。
- [SOAP](peer/java/README.md#soap)：Metro JAX-WS ↔ Meteor。
- [JSON-RPC](peer/java/README.md#jsonrpc)：jsonrpc4j ↔ Meteor。
- [XML-RPC](peer/java/README.md#xmlrpc)：Apache XML-RPC ↔ Meteor。
- [Hessian](peer/java/README.md#hessian)：Caucho Hessian ↔ Meteor。
- [gRPC](peer/java/README.md#grpc)：gRPC-Java ↔ Meteor。
- [gRPC-Web](peer/java/README.md#grpcweb)：Connect Kotlin/JVM → Meteor。
- [Connect](peer/java/README.md#connect)：Connect Kotlin/JVM → Meteor、Meteor → connect-rpc-java（社区）。
- [Triple](peer/java/README.md#triple)：Apache Dubbo Triple ↔ Meteor。
- [ONC RPC](peer/java/README.md#oncrpc)：RemoteTea ↔ Meteor。

### Python

[Python 环境准备](peer/python/README.md)。

- [gRPC](peer/python/README.md#grpc)：grpcio ↔ Meteor。
- [Thrift](peer/python/README.md#thrift)：Apache Thrift ↔ Meteor。
- [SOAP](peer/python/README.md#soap)：Zeep → Meteor、Meteor → soapfish2。
- [JSON-RPC](peer/python/README.md#jsonrpc)：Python 标准库 ↔ Meteor。
- [XML-RPC](peer/python/README.md#xmlrpc)：Python 标准库 ↔ Meteor。

<a id="node"></a>

### Node.js

[Node.js 环境准备](peer/node/README.md)。

- [Hprose](peer/node/README.md#hprose)：hprose ↔ Meteor。
- [JSON-RPC](peer/node/README.md#jsonrpc)：Node.js 标准库 ↔ Meteor。
- [Connect](peer/node/README.md#connect)：Connect SDK ↔ Meteor。

### Go

[Go 环境准备](peer/go/README.md)。

- [gRPC](peer/go/README.md#grpc)：grpc-go ↔ Meteor。
- [Connect](peer/go/README.md#connect)：connect-go ↔ Meteor。
- [Thrift](peer/go/README.md#thrift)：Apache Thrift Go SDK ↔ Meteor。

### C

- [ONC RPC](peer/c/README.md#oncrpc)：libtirpc ↔ Meteor，编译步骤见 [C 环境准备](peer/c/README.md)。

<a id="cpp"></a>

### C++

- [ONC RPC](peer/cpp/README.md#oncrpc)：libtirpc ↔ Meteor，编译步骤见 [C++ 环境准备](peer/cpp/README.md)。

<a id="browser"></a>

### 浏览器 JavaScript

- [gRPC-Web](peer/browser/README.md#grpcweb)：grpc-web → Meteor，页面构建与启动见[浏览器示例](peer/browser/README.md)。

<a id="shell"></a>

### Shell / curl

- [JSON-RPC](peer/shell/README.md#jsonrpc)：curl → Meteor，环境要求见 [Shell 示例](peer/shell/README.md)。

<a id="framework-integration"></a>

# 框架整合

演示容器自动发布同一订单服务、注入消费接口，并通过 ZooKeeper 注册发现。JSON-RPC（`/jsonrpc`）和 Hessian（`/hessian`）共用一个 HTTP 端口，同时提供 RSF 服务。

- **Hasor Boot**：[提供者](integration/java/hasor/src/main/java/net/hasor/example/integration/ProviderMain.java)、[消费者](integration/java/hasor/src/main/java/net/hasor/example/integration/ConsumerMain.java)、[配置](integration/java/hasor/src/main/resources)。
- **Solon**：[提供者](integration/java/solon/src/main/java/net/hasor/example/integration/ProviderMain.java)、[消费者](integration/java/solon/src/main/java/net/hasor/example/integration/ConsumerMain.java)、[配置](integration/java/solon/src/main/resources)。
- **Spring Boot**：[提供者](integration/java/spring/src/main/java/net/hasor/example/integration/ProviderMain.java)、[消费者](integration/java/spring/src/main/java/net/hasor/example/integration/ConsumerMain.java)、[配置](integration/java/spring/src/main/resources)。

先准备 ZooKeeper（默认 `127.0.0.1:2181`），再依次运行提供者、消费者的 `main`；消费者完成订单保存和查询后退出。服务地址由注册中心发现，消费协议由地址和已启用的连接器决定。

也可打包后运行。Hasor、Spring 使用各自的 `bootJar`；Solon 在 Gradle 下使用 Shadow 合并协议 SPI。以下以 Hasor 为例，在 Meteor 仓库根目录执行：

```bash
# 分别生成提供者、消费者 JAR
./gradlew -p met-example :integration:java:hasor:build
./gradlew -p met-example :integration:java:hasor:build \
  -PmainClass=net.hasor.example.integration.ConsumerMain

# 在两个终端分别启动
java -jar met-example/integration/java/hasor/build/libs/hasor-*-provider.jar
java -jar met-example/integration/java/hasor/build/libs/hasor-*-consumer.jar
```

将命令中的 `hasor` 换为 `solon` 或 `spring` 即可切换框架。也可跨框架配对调用。

<a id="management-api"></a>

## 管理 API

通过 Meteor Java API 查询注册中心信息、实例和服务目录：

- [Nacos](peer/java/meteor/src/main/java/net/hasor/example/NacosRegistryAdminMain.java)
- [ZooKeeper](peer/java/meteor/src/main/java/net/hasor/example/ZookeeperRegistryAdminMain.java)

<a id="resources"></a>

## 共享资源

- Java 订单[接口](peer/java/contracts/src/main/java/net/hasor/example/OrderDemoService.java)、[DTO](peer/java/contracts/src/main/java/net/hasor/example/Order.java)和[实现](peer/java/contracts/src/main/java/net/hasor/example/OrderDemoServiceImpl.java)位于 `peer/java/contracts`。Java SDK 按实现和必要版本分目录。
- Go [共享订单业务](peer/go/orders.go)。
- 协议契约 [order.proto](resource/order.proto)、[order.thrift](resource/order.thrift) 和 [order.x](resource/order.x)。
