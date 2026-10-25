# 官方 Thrift 测试契约

`interop.thrift` 是 Apache Thrift **0.25.0** 的测试契约。 生成源码位于 `meteor-test/peer/java/contracts/src/main/java/net/hasor/example/protocol/thrift/generated`，包名保持
`net.hasor.example.protocol.thrift.generated`，不进入 Meteor 主 JAR。 日常 Gradle 构建直接编译已签入的 Java，不执行 IDL 编译器，也不要求系统安装 Thrift。 生成代码保留官方头与格式，不手工调整字段、序列化逻辑或排版。

需要重生成时，在仓库根目录执行：

```bash
meteor-test/scripts/thrift/prepare-compiler.sh
THRIFT_COMPILER="$PWD/meteor-test/build/thrift-compiler-0.25.0/bin/thrift" \
  meteor-test/scripts/thrift/regenerate.sh
```

准备脚本只把官方源码与 Java generator 编译到 `meteor-test/build/` 目录，需要 `curl`、
`tar`、`sha256sum` 和 C++17 编译器，不安装软件或修改系统。 官方 release tar 已自带 lexer/parser，无需 Flex、Bison 或 CMake。 源包 SHA256 为 `66da4707214c54c94bac082103dc67adaf9e08925662700f269170a7b534b214`， 与 Apache 发布的校验值一致。Java 生成选项固定为 `java:generated_annotations=suppress`， 保留官方 `TBase`、`FieldMetaData`、Client 和 Processor，省略带日期的 `@Generated` 注解。

契约包括全部标量、二进制、list/map/set、enum、union、UUID、optional/defaults、 声明异常、非声明异常、普通 void、oneway、非顺序参数字段 ID 和服务继承。
`add(7: left, 2: right)` 用来验证 Java 参数按声明顺序绑定，而非按字段 ID 排序。
`ExtendedInteropService` 继承 `InteropService`；multiplexed 模式默认注册名为 `InteropService`。

官方对端入口为 `net.hasor.peer.protocol.thrift.ThriftPeer`，源码位于模块的
`peer/java/sdk/src/main/java/net/hasor/peer/protocol/thrift`。对端与生成契约分别使用 SDK 和 contracts 的标准 main 编译输出，独立 JVM 按所属 SDK 运行图启动，不复制 class，也不加载 Meteor 应用或 JUnit 驱动类。 TCP 使用官方 `TThreadPoolServer`；HTTP (S) 用 JDK 服务器承载官方生成的 Processor， 报文由官方 `TProtocol` 与 `TIOStreamTransport` 处理，客户端使用官方 `THttpClient`。

```text
--command server|client
--transport tcp|tls|http|https
--protocol binary|compact|json
--framed true|false
--multiplexed true|false
--service-name InteropService
--host 127.0.0.1
--port 0
--path /thrift
--key-store /absolute/path/to/identity.p12
--trust-store /absolute/path/to/trust.p12
--password changeit
--client-auth true|false
```

参数接受 `--name value` 和 `--name=value`。`framed` 只影响 TCP/TLS；HTTP 请求体直接装载 Thrift message。服务端成功监听后输出 `READY <port>`，父进程关闭 stdin 后正常释放资源。 TLS 使用 PKCS12 密钥库和信任库；未提供信任库时使用 JDK 默认信任，保留主机名验证。

客户端每次只执行一个 `--scenario`，成功输出 `RESULT <value>`：

| scenario                                                                                                 | 默认结果                                      |
|----------------------------------------------------------------------------------------------------------|-----------------------------------------------|
| `echo`、`extended`                                                                                       | `hello`，可用 `--value` 指定                  |
| `add`                                                                                                    | `42`，可用 `--left`、`--right` 指定           |
| `dto`、`optional`、`defaults`、`list`、`map`、`set`、`binary`、`enum`、`union`、`uuid`、`void`、`oneway` | `<scenario>-ok`，先校验实际响应               |
| `declared`                                                                                               | `declared:409:hello`，可用 `--value` 指定原因 |
| `unexpected`                                                                                             | `application:6`，官方 `INTERNAL_ERROR`        |
| `bad-method`                                                                                             | `application:1`，官方 `UNKNOWN_METHOD`        |
| `bad-mux`                                                                                                | `rejected:<异常简单类名>`，保留实际拒绝类型   |

`oneway` 先读取计数、发送通知，再有界等待服务计数增长，验证通知已被执行。 未知方法报文也由官方协议与生成的空参数结构写入。未知 multiplexed 服务的原生 Processor 可能直接关闭连接，因此该场景保留实际拒绝类型。

## 共享订单对端

`meteor-test/src/test/resources/order/order.thrift` 是测试自有的公共订单契约，对应测试中的普通 Java 注解接口：`save(Order)` 和 `find(OrderQuery)`。
订单字段编号为 `id=1`、`description=2`、`attachment=3`，查询字段为 `id=1`；
两个方法的参数编号均为 1，文本与附件为 optional。Java 测试直接使用普通接口和 DTO，
Python、Go 官方对端的生成源码分别位于 `meteor-test/peer/python/thrift/generated`、`meteor-test/peer/go/gen/thriftorder`。
Python 的生成器及源文件校验信息记录在 `meteor-test/peer/python/thrift/generated/provenance.json`，
Go 的重生成入口为 `meteor-test/peer/go/regenerate-thrift.sh`。

Java SDK 的订单 Client、Processor 和 DTO 使用官方 **0.22.0** 编译器生成，供 0.22.0 和当前 0.25.0 运行时共同使用。
0.25.0 生成代码引用旧运行时尚未提供的递归深度 API；订单生成器版本单独固定，其他 Thrift 测试契约仍使用 0.25.0。
在仓库根目录运行 `meteor-test/scripts/thrift/regenerate-sdk.sh`，脚本使用已有的 `meteor-test/native` Docker 镜像，
将官方源码、编译器和中间结果保存到 `meteor-test/build/sdk-compatibility/thrift-compiler-0.22.0/`。
源码 SHA256 为 `794a0e455787960d9f27ab92c38e34da27e8deeda7a5db0e59dc64a00df8a1e5`；
生成选项为 `java:generated_annotations=suppress`；签入文件仅统一 LF 并移除行尾空白，生成逻辑保持原样，
原始输出保留在上述目录的 `generated/` 中。可用 `METEOR_NATIVE_IMAGE` 指定已有工具链镜像。

经典 SDK 0.10.0 和 0.13.0 使用独立编译域 `meteor-test/peer/java/sdk/thrift-classic/`，
其中的订单契约由官方 **0.10.0** 编译器生成，入口仍为
`net.hasor.peer.sdk.thrift.SdkConsumerMain` 和 `SdkProviderMain`。
这一编译域使用 `org.apache.thrift.transport.TFramedTransport`，不依赖较新版本的 `transport.layered` 包。
两个 SDK 的 `TProcessor.process` 分别返回 boolean 和 void；生成 Processor 继承各自运行时的
`TBaseProcessor`，入口不直接调用该方法，避免把返回类型写入共享入口的字节码。
各运行 profile 只加载所属编译域与 SDK，不同时加载经典和现代订单绑定。

在仓库根目录运行 `meteor-test/scripts/thrift/regenerate-sdk-classic.sh` 重生成经典契约。
脚本使用已有的 native Docker 镜像，编译器、下载包和原始输出位于
`meteor-test/build/sdk-compatibility/thrift-compiler-0.10.0/`，生成选项仍为
`java:generated_annotations=suppress`；签入文件仅统一 LF 并移除行尾空白。
源码 SHA256 为 `2289d02de6e8db04cbbabb921aeb62bfe3098c4c83f36eec6c31194301efa10b`，
其 SHA1 与 Apache 归档提供的 `.sha` 文件一致。该官方校验文件内保留 `rc1` 文件名，
校验值对应下载的 `thrift-0.10.0.tar.gz`。

在仓库根目录重生成 Python 契约：

```bash
thrift --gen py -out meteor-test/peer/python/thrift/generated meteor-test/src/test/resources/order/order.thrift
```

`interop.thrift` 及其生成 Java 保留生成代码兼容、官方 SDK 对端和旧入口的回归覆盖，
不作为订单场景的运行依赖。
