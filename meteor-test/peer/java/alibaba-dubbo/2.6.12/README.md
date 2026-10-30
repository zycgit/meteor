# Alibaba Dubbo 2.6.12 对端

使用 `com.alibaba:dubbo:2.6.12` 的原始 API、Hessian 2 编码和远程 TCP 调用，复用测试自有订单接口和 DTO。
运行图包含同版 `dubbo-remoting-netty4`，由 SDK 自身 BOM 提供其 Netty 4 运行依赖。
`SdkConsumerMain` 验证 SDK → Meteor，`SdkProviderMain` 验证 Meteor → SDK。

这个标准 Java 子工程独立编译旧命名空间，运行图只包含本工程输出、公共契约及所属 SDK 依赖。
测试由 `meteor-test` 统一启动独立 JVM；不使用当前 Apache Dubbo 类替代旧 SDK。
