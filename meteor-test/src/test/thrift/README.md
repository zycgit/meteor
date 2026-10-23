# 官方 Thrift 测试契约

`interop.thrift` 是 Apache Thrift **0.25.0** 的测试契约。 生成源码位于 `../java/net/hasor/example/protocol/thrift/generated`，包名保持
`net.hasor.example.protocol.thrift.generated`，不进入 Meteor 主 JAR。 日常 Gradle 构建直接编译已签入的 Java，不执行 IDL 编译器，也不要求系统安装 Thrift。 生成代码保留官方头与格式，不手工调整字段、序列化逻辑或排版。

需要重生成时，在仓库根目录执行：

```bash
meteor-test/src/test/thrift/prepare-compiler.sh
THRIFT_COMPILER="$PWD/build/thrift-compiler-0.25.0/bin/thrift" \
  meteor-test/src/test/thrift/regenerate.sh
```

准备脚本只把官方源码与 Java generator 编译到忽略的 `build` 目录，需要 `curl`、
`tar`、`sha256sum` 和 C++17 编译器，不安装软件或修改系统。 官方 release tar 已自带 lexer/parser，无需 Flex、Bison 或 CMake。 源包 SHA256 为 `66da4707214c54c94bac082103dc67adaf9e08925662700f269170a7b534b214`， 与 Apache 发布的校验值一致。Java 生成选项固定为 `java:generated_annotations=suppress`， 保留官方 `TBase`、`FieldMetaData`、Client 和 Processor，省略带日期的 `@Generated` 注解。

契约包括全部标量、二进制、list/map/set、enum、union、UUID、optional/defaults、 声明异常、非声明异常、普通 void、oneway、非顺序参数字段 ID 和服务继承。
`add(7: left, 2: right)` 用来验证 Java 参数按声明顺序绑定，而非按字段 ID 排序。
`ExtendedInteropService` 继承 `InteropService`；multiplexed 模式默认注册名为 `InteropService`。

官方对端入口为 `net.hasor.peer.protocol.thrift.ThriftPeer`，源码位于模块的
`src/test/java/net/hasor/peer/protocol/thrift`。 对端与生成契约随标准测试源码编译一次，独立 JVM 直接复用测试 class 输出，不再复制 class。 复用已有测试运行依赖解析结果，仅选取 Thrift SDK 及其传递依赖。 目录中的其它测试类可见但不会自动执行；子 JVM 不继承完整测试或主工程运行依赖， 不加载 Meteor 生产输出、JUnit 或 Mockito。 TCP 使用官方 `TThreadPoolServer`；HTTP (S) 用 JDK 服务器承载官方生成的 Processor， 报文由官方 `TProtocol` 与 `TIOStreamTransport` 处理，客户端使用官方 `THttpClient`。

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

`order.thrift` 对应 `met-example` 的注解接口：`save(Order)` 和 `find(OrderQuery)`。
订单字段编号为 `id=1`、`description=2`、`attachment=3`，查询字段为 `id=1`；
两个方法的参数编号均为 1，文本与附件为 optional。Java 示例直接使用普通接口和 DTO，
Python、Go 官方对端的生成源码分别位于 `../python/thrift/generated`、`../go/gen/thriftorder`。
Python 的生成器及源文件校验信息记录在 `../python/thrift/generated/provenance.json`，
Go 的重生成入口为 `../go/regenerate-thrift.py`。

在仓库根目录重生成 Python 契约：

```bash
thrift --gen py -out meteor-test/src/test/python/thrift/generated meteor-test/src/test/thrift/order.thrift
```

`interop.thrift` 及其生成 Java 保留生成代码兼容、官方 SDK 对端和旧入口的回归覆盖，
不作为订单示例的运行依赖。
