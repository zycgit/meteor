# 浏览器 gRPC-Web 示例

## 介绍

使用 grpc-web SDK 调用 Meteor 发布的订单服务。

## 特性

- 保存并查询订单 `1001`。
- 通过 Protobuf 传输，附件以 Base64 展示。

## 用法

需要 JDK 17、Node.js 22 和浏览器。RPC 地址固定为 `http://127.0.0.1:8080/grpc-web`，页面为 `http://127.0.0.1:8090`。

## Demo 如何启动

### 直连

<a id="sdk-to-meteor"></a>

#### grpc-web → Meteor

- SDK：grpc-web，消息定义为 [order.proto](../../resource/order.proto)。
- 提供者：[Meteor ProviderMain](../../java/meteor/src/main/java/net/hasor/example/grpcweb/ProviderMain.java)。
- 消费者：[consumer.js](consumer.js)，在 [index.html](index.html) 中运行。

先在 IDEA 运行上述提供者的 `main`。

在仓库根目录运行以下命令启动页面：

```bash
npm --prefix met-example/browser/grpcweb ci
npm --prefix met-example/browser/grpcweb run build
npm --prefix met-example/browser/grpcweb start
```

打开 `http://127.0.0.1:8090`，点击“保存并查询”。按 Ctrl+C 停止服务。

返回[示例总目录](../../README.md)。
