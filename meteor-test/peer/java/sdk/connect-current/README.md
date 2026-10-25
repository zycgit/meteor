# Connect Kotlin 当前版本对端

使用官方 Connect Kotlin 0.10.0，分别以 Connect 和 gRPC-Web 调用 Meteor。两个消费者保留相同主类名，
由独立 SDK 运行 profile 选择本目录的编译结果；订单业务和 Protobuf 消息复用 `meteor-test-contracts`。

- `net.hasor.peer.sdk.connect.SdkConsumerMain`：Connect 消费者。
- `net.hasor.peer.sdk.grpcweb.SdkConsumerMain`：gRPC-Web 消费者。

当前版本独立解析 OkHttp 5、Kotlin 2 和 Protobuf 4，不能加入历史版本的编译及运行类路径。
公共订单消息由 Protobuf 3.25.9 生成，继续复用；此 SDK 的官方扩展依赖 Protobuf 4.36.2，
运行时采用实际解析版本，不把 Protobuf 降级到公共契约的编译版本。

入口使用官方 `ProtocolClient`、`GoogleJavaProtobufStrategy` 和回调 API。超时、中断、错误和取消保留原始结果；
HTTP 连接池与执行线程由创建它们的入口释放。该 SDK 只提供消费者，不补造服务端实现。
