# 浏览器 JavaScript 示例

[返回示例目录](../../README.md)

## 运行准备

准备 Node.js 22 或更新版本和浏览器，按[统一启动约定](../../README.md#run)准备 Java 端。以下命令从 Meteor 仓库根目录执行。

<a id="grpcweb"></a>

## gRPC-Web

使用 grpc-web SDK、Protobuf 和 [order.proto](../../resource/order.proto) 调用订单服务。RPC 地址为 `http://127.0.0.1:8080/grpc-web`，页面地址为 `http://127.0.0.1:8090`。

### grpc-web → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain](../java/meteor/src/main/java/net/hasor/example/grpcweb/ProviderMain.java)，再安装依赖、构建并启动页面：

```bash
npm --prefix met-example/peer/browser/grpcweb ci
npm --prefix met-example/peer/browser/grpcweb run build
npm --prefix met-example/peer/browser/grpcweb start
```

打开 `http://127.0.0.1:8090`，点击“保存并查询”，页面显示订单 `1001` 的查询结果，附件以 Base64 展示。按 Ctrl+C 停止页面服务。

消费者源码为 [consumer.js](grpcweb/consumer.js)，页面入口为 [index.html](grpcweb/index.html)。
