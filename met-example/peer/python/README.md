# Python 示例

[返回示例目录](../../README.md)

## 运行准备

使用 Python 3，并准备 `venv`、`pip`。按[统一启动约定](../../README.md#run)准备 Java 端，以下命令从 Meteor 仓库根目录执行。

先创建虚拟环境；新终端运行 Python 示例前也需激活：

```bash
python3 -m venv met-example/build/python-venv
source met-example/build/python-venv/bin/activate
```

gRPC、Thrift、SOAP 按对应小节安装依赖；JSON-RPC、XML-RPC 使用标准库。消费者保存并查询订单 `1001`，输出订单 JSON。

协议：[gRPC](#grpc) · [Thrift](#thrift) · [SOAP](#soap) · [JSON-RPC](#jsonrpc) · [XML-RPC](#xmlrpc)。

## gRPC

grpcio 使用 HTTP/2、Protobuf 和 [order.proto](../../resource/order.proto)，生成代码位于 [grpc/generated](grpc/generated)。地址为 `127.0.0.1:8080/grpc`。

```bash
python -m pip install -r met-example/peer/python/grpc/requirements.txt
```

### grpcio → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain.java](../java/meteor/src/main/java/net/hasor/example/grpc/ProviderMain.java)，再执行消费者 [consumer.py](grpc/consumer.py)：

```bash
python met-example/peer/python/grpc/consumer.py
```

### Meteor → grpcio（直连）

先启动提供者 [provider.py](grpc/provider.py)：

```bash
python met-example/peer/python/grpc/provider.py
```

随后在 IDEA 运行 Meteor [ConsumerMain.java](../java/meteor/src/main/java/net/hasor/example/grpc/ConsumerMain.java)。

## Thrift

Apache Thrift 使用 Binary 编码、Framed 传输和 [order.thrift](../../resource/order.thrift)，生成代码位于 [thrift/generated](thrift/generated)。地址为 `127.0.0.1:8080`。

```bash
python -m pip install -r met-example/peer/python/thrift/requirements.txt
```

### Apache Thrift → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain.java](../java/meteor/src/main/java/net/hasor/example/thrift/ProviderMain.java)，再执行消费者 [consumer.py](thrift/consumer.py)：

```bash
python met-example/peer/python/thrift/consumer.py
```

### Meteor → Apache Thrift（直连）

先启动提供者 [provider.py](thrift/provider.py)：

```bash
python met-example/peer/python/thrift/provider.py
```

随后在 IDEA 运行 Meteor [ConsumerMain.java](../java/meteor/src/main/java/net/hasor/example/thrift/ConsumerMain.java)。

## SOAP

SOAP 1.1，Zeep 消费者读取 WSDL，soapfish2 提供者自动生成 WSDL。地址为 `http://127.0.0.1:8080/soap/orders`。

```bash
python -m pip install -r met-example/peer/python/soap/requirements.txt
```

### Zeep → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain.java](../java/meteor/src/main/java/net/hasor/example/soap/ProviderMain.java)，再执行消费者 [consumer.py](soap/consumer.py)：

```bash
python met-example/peer/python/soap/consumer.py
```

### Meteor → soapfish2（直连）

先启动提供者 [provider.py](soap/provider.py)：

```bash
python met-example/peer/python/soap/provider.py
```

随后在 IDEA 运行 Meteor [ConsumerMain.java](../java/meteor/src/main/java/net/hasor/example/soap/ConsumerMain.java)。

<a id="jsonrpc"></a>

## JSON-RPC

标准库实现 JSON-RPC 2.0，按位置传参，附件使用 Base64。地址为 `http://127.0.0.1:8080/jsonrpc`。

### urllib.request → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain.java](../java/meteor/src/main/java/net/hasor/example/jsonrpc/ProviderMain.java)，再执行消费者 [consumer.py](jsonrpc/consumer.py)：

```bash
python met-example/peer/python/jsonrpc/consumer.py
```

### Meteor → http.server（直连）

先启动提供者 [provider.py](jsonrpc/provider.py)：

```bash
python met-example/peer/python/jsonrpc/provider.py
```

随后在 IDEA 运行 Meteor [ConsumerMain.java](../java/meteor/src/main/java/net/hasor/example/jsonrpc/ConsumerMain.java)。

<a id="xmlrpc"></a>

## XML-RPC

标准库实现 XML-RPC，订单映射为 struct，附件使用 Base64。地址为 `http://127.0.0.1:8080/xmlrpc`。

### xmlrpc.client → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain.java](../java/meteor/src/main/java/net/hasor/example/xmlrpc/ProviderMain.java)，再执行消费者 [consumer.py](xmlrpc/consumer.py)：

```bash
python met-example/peer/python/xmlrpc/consumer.py
```

### Meteor → xmlrpc.server（直连）

先启动提供者 [provider.py](xmlrpc/provider.py)：

```bash
python met-example/peer/python/xmlrpc/provider.py
```

随后在 IDEA 运行 Meteor [ConsumerMain.java](../java/meteor/src/main/java/net/hasor/example/xmlrpc/ConsumerMain.java)。
