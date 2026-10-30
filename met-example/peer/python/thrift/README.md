# Python Thrift 订单服务

## 介绍

使用 Apache Thrift Python SDK 演示 Python 与 Meteor 双向调用订单服务。

## 特性

binary 编码与 framed 传输。使用公共 [order.thrift](../../../resource/order.thrift)，生成代码位于 `generated/`。

## 用法

固定地址为 `127.0.0.1:8080`。消费者保存并查询订单 `1001`，输出订单 JSON。

安装依赖：

```bash
python -m pip install -r met-example/peer/python/thrift/requirements.txt
```

命令从 Meteor 仓库根目录执行，Java 端需要 JDK 17。

## Demo 如何启动

### 直连

每组先启动提供者，再在另一终端运行消费者。切换方向前停止原提供者。

<a id="sdk-to-meteor"></a>

#### Apache Thrift → Meteor

- 提供者：Meteor，[ProviderMain.java](../../java/meteor/src/main/java/net/hasor/example/thrift/ProviderMain.java)。
- 消费者：Apache Thrift，[consumer.py](consumer.py)。

先在 IDEA 运行上述提供者的 `main`，再运行消费者：

```bash
python met-example/peer/python/thrift/consumer.py
```

<a id="meteor-to-sdk"></a>

#### Meteor → Apache Thrift

- 提供者：Apache Thrift，[provider.py](provider.py)。
- 消费者：Meteor，[ConsumerMain.java](../../java/meteor/src/main/java/net/hasor/example/thrift/ConsumerMain.java)。

先启动提供者：

```bash
python met-example/peer/python/thrift/provider.py
```

随后在 IDEA 运行上述消费者的 `main`。
