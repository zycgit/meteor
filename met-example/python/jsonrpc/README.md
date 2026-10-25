# Python JSON-RPC 订单服务

## 介绍

使用 Python 标准库 演示 Python 与 Meteor 双向调用订单服务。

## 特性

JSON-RPC 2.0，按位置传参，附件使用 Base64。

## 用法

固定地址为 `http://127.0.0.1:8080/jsonrpc`。消费者保存并查询订单 `1001`，输出订单 JSON。

命令从 Meteor 仓库根目录执行，Java 端需要 JDK 17。

## Demo 如何启动

### 直连

每组先启动提供者，再在另一终端运行消费者。切换方向前停止原提供者。

<a id="sdk-to-meteor"></a>

#### urllib.request → Meteor

- 提供者：Meteor，[ProviderMain.java](../../java/meteor/src/main/java/net/hasor/example/jsonrpc/ProviderMain.java)。
- 消费者：urllib.request，[consumer.py](consumer.py)。

先在 IDEA 运行上述提供者的 `main`，再运行消费者：

```bash
python met-example/python/jsonrpc/consumer.py
```

<a id="meteor-to-sdk"></a>

#### Meteor → http.server

- 提供者：http.server，[provider.py](provider.py)。
- 消费者：Meteor，[ConsumerMain.java](../../java/meteor/src/main/java/net/hasor/example/jsonrpc/ConsumerMain.java)。

先启动提供者：

```bash
python met-example/python/jsonrpc/provider.py
```

随后在 IDEA 运行上述消费者的 `main`。
