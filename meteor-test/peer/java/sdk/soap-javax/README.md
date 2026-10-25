# Metro JAX-WS javax 对端

使用 Metro 2.x 的 `javax.jws`、`javax.xml.ws` 和 JAXB API，验证旧版 Java SOAP SDK 与 Meteor 的双向互通。

- SDK → Meteor：`net.hasor.peer.sdk.soap.SdkConsumerMain`。
- Meteor → SDK：`net.hasor.peer.sdk.soap.SdkProviderMain`。

订单业务和进程配置复用 `meteor-test-contracts`。SOAP 绑定独立编译，运行时只加载本模块、公共契约和对应 Metro 版本依赖，
不加载主 SDK 的 Jakarta SOAP 类。入口名称、参数及订单内容与当前 Metro 对端相同，由版本矩阵选择隔离的 JVM classpath。
