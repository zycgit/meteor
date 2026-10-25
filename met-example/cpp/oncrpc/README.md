# C++ 与 Meteor ONC RPC 示例

## 介绍

C++ 端使用 libtirpc，与 Java Meteor 互相调用订单服务。

## 特性

- 使用公共 [order.x](../../resource/order.x) 契约，XDR 编码、TCP 直连。
- 固定地址 `127.0.0.1:8080`，订单编号 `1001`，无需 rpcbind。

## 用法

消费者保存订单再查询，输出编号、描述和字节数组附件。

## Demo 如何启动

准备 JDK 17、C++17 编译器、Make、rpcgen 和 libtirpc 开发包。以下命令从仓库根目录执行；每个方向使用两个终端，切换前停止提供者。

先编译 C++ 示例：

```bash
make -C met-example/cpp
```

### 直连

<a id="sdk-to-meteor"></a>

#### libtirpc（C++） → Meteor

- 提供者：Meteor [ProviderMain](../../java/meteor/src/main/java/net/hasor/example/oncrpc/ProviderMain.java)。
- 消费者：libtirpc [consumer.cpp](consumer.cpp)。

先在 IDEA 运行上述提供者的 `main`，再运行消费者：

```bash
met-example/build/native/cpp/oncrpc/consumer
```

<a id="meteor-to-sdk"></a>

#### Meteor → libtirpc（C++）

- 提供者：libtirpc [provider.cpp](provider.cpp)。
- 消费者：Meteor [ConsumerMain](../../java/meteor/src/main/java/net/hasor/example/oncrpc/ConsumerMain.java)。

先启动提供者：

```bash
met-example/build/native/cpp/oncrpc/provider
```

随后在 IDEA 运行上述消费者的 `main`。
