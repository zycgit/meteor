# Meteor

Meteor 是面向 **Java** 的多协议 RPC 框架。使用普通 Java 接口发布和消费服务，同一业务实现可以同时开放多个 RPC 协议，与 Java、Python、Go、Node.js、浏览器、C 和 C++ 的 SDK 互通。

协议、注册中心和框架整合按模块引入。既可以直接使用 Java API，也可以在 Hasor、Spring、Solon 容器中自动发布服务、注入消费接口。

[快速开始](#快速开始) · [兼容矩阵](#兼容矩阵) · [SDK 版本](#sdk-版本兼容测试) · [框架整合](#框架整合) · [开发示例](met-example/README.md)

## 支持特性

- **多协议服务**：同一接口、多协议发布、独立服务别名、按服务选择协议与挂载路径。
- **契约映射**：公共注解、普通 DTO、自动生成 Protobuf / WSDL / XSD / Thrift IDL / XDR、绑定已有契约。
- **网络传输**：TCP、UDP、HTTP/1.1、HTTP/2、WebSocket、h2c、HTTP 多协议按 context path 复用端口。
- **安全通信**：TLS、DTLS、HTTPS、WSS、双向认证、ALPN。
- **调用模式**：请求响应、消息、JDK Flow 流、超时、取消、背压、半关闭；范围由协议决定。
- **SOAP 能力**：SOAP 1.1/1.2、WSDL 1.1、关联 XSD、document / RPC、literal / encoded、Header / Fault、MTOM / XOP / SwA。
- **服务发现**：直连、本地 XML 地址本、Nacos / ZooKeeper / JDBC 注册存储、Dubbo 原生注册中心桥接。
- **服务治理**：配置同步、服务元数据、XML 治理规则、控制台管理。

## 快速开始

使用 JDK 17，从 Maven Central 引入所需的协议模块，Meteor 内核随协议模块传递引入。下面以 JSON-RPC Echo 服务为例：

```xml
<dependency>
    <groupId>net.hasor</groupId>
    <artifactId>meteor-protocol-jsonrpc</artifactId>
    <version>4.2.6</version>
</dependency>
```

将下面代码保存为 `EchoServer.java`，运行其 `main` 方法：

```java
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;

public class EchoServer {
    public interface EchoService {
        String echo(String message);
    }

    public static void main(String[] args) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        configuration.connector("http").bind("127.0.0.1", 8080)
                .protocol("jsonrpc").contextPath("/jsonrpc");

        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(EchoService.class, message -> message)
                    .protocol("jsonrpc").aliasName("jsonrpc", "echo").register();
            System.out.println("服务已启动，按 Enter 关闭。");
            System.in.read();
        }
    }
}
```

在另一终端发起调用，响应的 `result` 为 `"Hello, Meteor!"`：

```bash
curl http://127.0.0.1:8080/jsonrpc \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"echo.echo","params":["Hello, Meteor!"],"id":1}'
```

Java 接口代理、注册发现和多协议发布见[完整示例](met-example/README.md)。也可在 IDEA 中单独打开 `met-example`，直接运行对应的提供者与消费者。

## 兼容矩阵

每格表示该对端与 Meteor 的基础调用方向：**● 双向已验证；◐ 对端 → Meteor；◑ Meteor → 对端；○ 所列 SDK 验证不兼容；— 未验证。**

| 协议 | Meteor 自体 | Java SDK | Python | Go | Node.js | 浏览器 | C | C++ | curl |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| RSF | ● | — | — | — | — | — | — | — | — |
| gRPC | ● | ● | ● | ● | ● | — | ● | ● | ◐ |
| gRPC-Web | ● | ◐\* | ●\* | ● | ● | ◐ | — | — | ◐ |
| Hprose | ● | ● | ● | ● | ● | ◐ | — | ◐\* | ◐ |
| SOAP | ● | ● | ●\* | ◐ | ● | ◐ | ● | ● | ◐ |
| JSON-RPC | ● | ● | ● | ● | ● | ◐ | ◐\* | ● | ◐ |
| XML-RPC | ● | ● | ● | ◐\* | ● | ◐ | ● | ● | ◐ |
| Hessian | ● | ● | ◐ | — | ◐ | — | — | ◐\* | ◐ |
| Connect | ● | ●\* | ●\* | ● | ● | ◐ | — | ◐\* | ◐ |
| Triple | ● | ● | ●\* | ● | ●\* | ◐\* | ● | ● | ◐ |
| Dubbo | ● | ● | ○ | ◐ | ◐ | — | — | — | — |
| Thrift | ● | ● | ● | ● | ● | ◐ | ● | ● | ◐ |
| ONC RPC | ● | ● | ●\* | ●\* | ●\* | — | ● | ● | — |

\* 表示该格包含预发布、开发版或固定源码构建的 SDK，具体范围如下：

- **Connect / gRPC-Web**：Java 的 Connect Kotlin 0.5.1、Python 的 connectrpc 0.13.0 为 Beta；**Python SOAP** 的服务端使用 soapfish2 0.7.4 Beta。
- **Go XML-RPC** 使用 kolo/xmlrpc 源码快照；**ONC RPC** 使用 Python sunrpc 1.1.0 开发版、Go sunrpc 和 Node.js node-oncrpc 固定源码快照。
- **Triple**：Node.js 与浏览器使用 Apache Dubbo 3.0.0-alpha；Python 使用 grpcio 1.84.0 和 apache-dubbo 3.0.0b1，所列方向通过。Python Beta 使用 `application/grpc+data`，插件兼容其 unary 尾部空帧标记。
- **C JSON-RPC** 使用 json-rpc-client 固定源码；**C++ Hprose** 使用 hprose-cpp1x 固定源码及其公开传输扩展点接入 libcurl，未使用原 HTTP 客户端。
- **C++ Connect** 使用 Loams 0.1.3 源码发行及独立 Protobuf/curl 工具链，只验证 SDK → Meteor 的 Protobuf unary。
- **C++ Hessian** 使用 hessiancpp 1.1.0 源码及独立旧工具链，验证原 HTTP 客户端的 Hessian 1 调用；星号表示源码构建条件，不表示它是预发布版。

117 个组合中，94 个有基础调用通过证据，1 个仅有所列 SDK 的失败证据，22 个保留未验证及原因。RSF 当前仅保留 Meteor 自体调用。半圆表示当前只验证该方向。具体 SDK、版本、测试方法及代理限制见 互通证据；发行版本范围见 [SDK 版本兼容](#sdk-版本兼容测试)，类型限制见 类型矩阵，最近执行结果见 [report.md](meteor-test/report.md)。

## SDK 版本兼容测试

下列版本均有基础调用通过记录；除标注方向的条目外，均已验证 SDK ↔ Meteor。版本号为已验证版本，不表示其间所有版本均已覆盖。

| 语言 | 协议 / SDK | 已验证版本 | 特性概要 |
| --- | --- | --- | --- |
| Java / JVM | gRPC / grpc-java | 1.84.1、1.85.1 | Protobuf、HTTP/2 |
| Java / JVM | Hprose / Hprose Java | 2.0.38 | Hprose 2、HTTP |
| Java / JVM | SOAP / Metro JAX-WS | 2.3.7、3.0.2、4.0.5 | SOAP 1.1/1.2、javax / jakarta |
| Java / JVM | JSON-RPC / jsonrpc4j | 1.7 | JSON-RPC 2.0 |
| Java / JVM | XML-RPC / Apache XML-RPC | 3.1.3 | XML-RPC、扩展类型 |
| Java / JVM | Hessian / Caucho Hessian | 4.0.51、4.0.63、4.0.66 | Hessian 1/2 |
| Java / JVM | Connect / gRPC-Web / Connect Kotlin | 0.4.0、0.5.1、0.10.0 | Connect / gRPC-Web、Protobuf；SDK → Meteor |
| Java / JVM | Connect / connect-rpc-java | 0.2.1 | Connect 提供者；Meteor → SDK |
| Java / JVM | Triple / Apache Dubbo | 3.2.20、3.3.6 | Triple、Protobuf |
| Java / JVM | Dubbo / Alibaba、Apache Dubbo | 2.6.12、2.7.23、3.3.6 | Dubbo、Hessian2、注册发现 |
| Java / JVM | Thrift / Apache Thrift | 0.10.0、0.13.0、0.16.0、0.22.0、0.25.0 | Binary / Compact / JSON |
| Java / JVM | ONC RPC / RemoteTea | 1.1.6 | ONC RPC、XDR |
| Python | gRPC / grpcio | 1.85.0 | gRPC、Protobuf |
| Python | Connect、gRPC-Web / connectrpc | 0.10.1、0.11.1、0.13.0 | Connect / gRPC-Web |
| Python | SOAP / Zeep | 3.4.0、4.3.3 | SOAP、WSDL；SDK → Meteor |
| Python | SOAP / soapfish2 | 0.7.4 | SOAP 提供者；Meteor → SDK |
| Python | Thrift / Apache Thrift | 0.13.0、0.16.0、0.22.0、0.25.0 | Binary / Compact / JSON |
| Python | Hprose / python-hessian | 1.4.3 / 1.2.0 | Hprose 双向；Hessian 为 SDK → Meteor |
| Python | Triple / apache-dubbo | 3.0.0b1（Beta） | Triple、普通调用 |
| Go | gRPC / grpc-go | 1.84.0、1.85.0 | gRPC、Protobuf |
| Go | Connect / connect-go | 1.19.1、1.21.1 | Connect、Protobuf |
| Go | Hprose / hprose-golang | 2.0.6、3.0.16 | Unicode、应用异常 |
| Go | Dubbo / Dubbo-Go | 1.5.9、3.3.2 | Dubbo、Hessian2 |
| Go | Thrift / Apache Thrift | 0.13.0、0.14.2、0.18.1、0.25.0 | Binary / framed、UUID（0.18.1 / 0.25.0） |
| Node.js | Connect / Connect-ES | 1.7.0、2.2.0、2.3.0 | Connect、Protobuf-ES |
| Node.js | JSON-RPC / Jayson | 2.1.2、3.7.0、4.3.0、5.0.0 | 位置参数、字符串 ID、Unicode |
| Node.js | SOAP / node-soap | 0.45.0、1.14.0 | SOAP、WSDL、document / literal |
| Node.js | Thrift / Apache Thrift | 0.12.0、0.14.2、0.23.0、0.25.0 | Binary / Compact / JSON、i64、BigInt（0.25.0） |
| Node.js | Dubbo / dubbo2.js | 1.0.4、2.3.9 | Dubbo；SDK → Meteor |
| Node.js | Hprose / node-xmlrpc | 2.0.51 / 1.3.2 | Hprose / XML-RPC |
| Node.js | Hessian / hessian-proxy | 0.0.5 | SDK → Meteor |
| Node.js / 浏览器 | Triple / Apache Dubbo | 3.0.0-alpha | 浏览器为 SDK → Meteor |
| 浏览器 | gRPC-Web / grpc-web | 1.5.0、2.1.1 | binary / text；SDK → Meteor |
| C / C++ | gRPC / gRPC Core C、C++ | 1.51.1、1.85.0 | gRPC、Protobuf |
| C / C++ | Thrift / C GLib、C++ | 0.17.0、0.25.0 | Binary / framed |
| C / C++ | SOAP / gSOAP | 2.8.124、2.8.146 | SOAP 1.1、文本调用 |
| C / C++ | XML-RPC / xmlrpc-c | 1.33.14、1.64.04 | XML-RPC |
| C / C++ | ONC RPC / libtirpc | 1.3.8 | ONC RPC、TCP、XDR |
| C++ | JSON-RPC / libjson-rpc-cpp | 0.7.0、1.4.1 | JSON-RPC |
| C++ | Connect / Loams | 0.1.3 | Connect、Protobuf 普通调用；SDK → Meteor |
| C++ | HessianCpp | 1.1.0 | Hessian 1、HTTP；SDK → Meteor |

基础调用通过不代表全部 TLS、流式和类型能力均通过。Dubbo TLS / mTLS 已验证组合为 **Dubbo 3.3.6 + Netty 4.1.113.Final + JDK 17**，范围为证书链与双向认证。

最近执行结果与未解决问题见[测试报告](meteor-test/report.md)。

## 框架整合

复用接口上的 `@MetService`，支持 Bean 自动发布、消费代理注入、注册发现与容器生命周期管理。

| 框架 | 已验证版本 | 整合模块 |
| --- | --- | --- |
| Hasor | 5.3.1-SNAPSHOT | `net.hasor:meteor-hasor` |
| Spring Framework | 6.2.18 | `net.hasor:meteor-spring` |
| Solon | 4.1.0 | `net.hasor:meteor-solon` |

配置与可运行 JAR 示例见[框架示例](met-example/README.md#framework-integration)。

## 模块

| 模块 / 目录 | 用途 |
| --- | --- |
| [meteor](meteor) | 内核、服务发布消费、地址管理、连接器、内置 RSF |
| [meteor-integration/protocol-*](meteor-integration) | gRPC / gRPC-Web、Connect、Dubbo、Triple、Thrift 等协议插件 |
| [meteor-registry](meteor-registry) | 注册中心、服务发现、管理 API |
| [meteor-integration/registry-*](meteor-integration) | JDBC、Nacos、ZooKeeper 存储与 Dubbo 原生注册中心桥接 |
| [meteor-integration/meteor-*](meteor-integration) | Hasor、Spring、Solon 框架整合 |
| [meteor-console](meteor-console) | Web 控制台、注册中心管理 |
| [met-example](met-example/README.md) | 各语言协议示例、框架整合示例 |
| [meteor-test](meteor-test/README.md) | SDK 互通、跨语言矩阵、框架与注册中心集成测试 |
| `document` | 使用指南、协议手册、博客与开发资料 |

## 源码构建

在仓库根目录使用 JDK 17：

```bash
./build.sh package       # 清理并打包产品模块
./build.sh install       # 安装到 build/maven-repository
./build.sh install test  # 同时运行产品模块测试
./build.sh web           # 构建前端资源到 meteor-console
```

根脚本仅构建产品模块。NXN 交叉测试使用独立入口，运行方式见[测试工程说明](meteor-test/README.md)。

## 支持边界

- gRPC 标准互通使用 Protobuf；自定义编码需双方配置匹配的 marshaller。gRPC-Web 支持普通调用和服务端流，Hprose 插件支持 2.x over HTTP。
- SOAP 尚不支持通用 XSD 自动类生成、`redefine`、通用异构 `choice` 自动绑定及 WS-* 扩展。
- 可调用远程对象、基于对象引用的回调、Java RMI / JRMP、Cap’n Proto RPC 不在支持范围。

## 示例与测试

- [开发示例](met-example/README.md)：按语言和协议选择提供者、消费者。
- [Java 协议互通](met-example/peer/java/README.md)：Meteor 与各 Java SDK 的调用示例。
- [框架整合](met-example/README.md#framework-integration)：Hasor、Solon、Spring 发布与消费服务。
- [测试工程](meteor-test/README.md)：Docker 交叉测试的运行入口。
- [测试报告](meteor-test/report.md)：实际执行结果和已知问题。

## 许可证

Meteor 使用 [Apache License 2.0](LICENSE.txt)。
