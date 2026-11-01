# C++ 示例

[返回示例目录](../../README.md)

## 运行准备

准备 C++17 编译器、C 编译器、Make、rpcgen 和 libtirpc 开发包。按[统一启动约定](../../README.md#run)准备 Java 端，以下命令从 Meteor 仓库根目录执行。

编译时会从 [order.x](../../resource/order.x) 生成契约绑定，产物位于 `met-example/build/native/cpp`：

```bash
make -C met-example/peer/cpp
```

<a id="oncrpc"></a>

## ONC RPC

使用 libtirpc、XDR 编码和 TCP 直连，地址为 `127.0.0.1:8080`，无需 rpcbind。消费者保存并查询订单 `1001`，输出编号、描述和字节数组附件。

### libtirpc（C++） → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain](../java/meteor/src/main/java/net/hasor/example/oncrpc/ProviderMain.java)，再执行消费者 [consumer.cpp](oncrpc/consumer.cpp)：

```bash
met-example/build/native/cpp/oncrpc/consumer
```

### Meteor → libtirpc（C++）（直连）

先启动提供者 [provider.cpp](oncrpc/provider.cpp)：

```bash
met-example/build/native/cpp/oncrpc/provider
```

随后在 IDEA 运行 Meteor [ConsumerMain](../java/meteor/src/main/java/net/hasor/example/oncrpc/ConsumerMain.java)。
