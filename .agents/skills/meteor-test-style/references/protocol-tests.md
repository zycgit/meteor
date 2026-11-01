# 协议插件测试

适用于 `meteor-integration/meteor-protocol-*` 与 `meteor-test`；与仓库 `AGENTS.md`、主 Skill 一起使用。

## 分层与归属

单元测试和单协议基础功能测试保留在协议插件；官方对端、多语言、注册发现及跨协议组合测试统一归入 `meteor-test`。测试宿主独立持有业务模型、契约、对端及 SDK 运行依赖，与 `met-example` 互不依赖；插件不依赖这两个工程。

1. `net.hasor.meteor.protocol.<protocol>` 及实际实现子包：编解码、签名解析、配置匹配、帧边界、状态转换等单测；直接访问同包实现，不必建立网络连接。
2. 插件的 `net.hasor.realmeteor.protocol.<protocol>`：发布、订阅和当前协议必要的基础行为，从 bootstrap 启动，不为测试装配其他协议或外部 SDK 对端；实现本身必需的正式依赖仍按生产职责使用。`meteor-test` 下同一协议包负责 self、provider、consumer 三条互通链路及对应元数据、流、期限、取消和安全组合场景。
3. 测试工程内跨协议共享的公共接口、DTO 和生成绑定放在 `meteor-test/peer/java/contracts/src/main/java/net/hasor/example`，Meteor 业务实现归 `peer/java/meteor/src/main/java`，Meteor 应用归 `meteor-test/peer/java/meteor/src/main/java`，外部 SDK 启动入口归 `meteor-test/peer/java/<SDK 名称>/src/main/java`，不兼容版本使用该 SDK 下的显式版本目录；协议专用的小型测试模型可保留在各自模块的 `net.hasor.example.protocol.<protocol>`。辅助类不承载 `@Test`，不从另一个测试类导入业务模型。
4. 外部客户端、原始帧故障注入及同步工具放在使用范围内的测试包或其 `support` 子包，只模拟对端或控制时序，不模拟待验证的 Meteor 协议逻辑。
5. HTTP 传输本身的用例属于内核传输测试；只有验证协议在该传输上的行为时才放入协议插件。
6. 全插件共存、共享端口、安全组合及调用隔离场景归入 `meteor-test`，相同场景只保留一份。全部语言对端按 `meteor-test/peer/<语言>` 组织，Java 应用、JUnit 场景、公共契约与 SDK 对端分别归 `peer/java/meteor`、`peer/java/contracts`、`peer/java/<SDK 名称>`；SDK 运行依赖按实际版本和角色自行声明；相同 SDK 图在测试工程内部共享，只有版本、提供者角色或实际依赖不同才另设配置。不从宿主依赖树裁剪 SDK，不复制 class 或增加自定义编译源集。
7. Node.js、Python、Go 和 C 测试对端分别放在 `meteor-test/peer/node`、`peer/python`、`peer/go`、`peer/c`。镜像构建提供工具链与 SDK，测试宿主管理就绪、超时、日志和退出；测试对端保留场景所需控制参数，不套用 Demo 固定地址和端口。
8. 基础订单契约使用 `meteor-test/resource/protocol/proto/order.proto`、`meteor-test/resource/protocol/thrift/order.thrift`、`meteor-test/resource/protocol/x/order.x`，生成代码随实际使用它的 SDK 保留，不能把生成代码当作重复契约删除。共享契约按语法归 `resource/protocol/{proto,thrift,x,wsdl,xsd}`，共享测试数据归 `resource/data`，证书归 `resource/certs`；Java 专属 fixture 和 SPI 归 Java 模块的标准资源目录，NXN 配置归外层 `src/main/resources/matrix`；生成维护工具归 `tools`，运行必需脚本归所属语言对端目录。

迁包后删除不再需要的访问适配器。跨包构造的必要例外遵循主技能，不扩大生产 API 或可见性。

## 场景的可读结构

```java
@Test
public void jsonProxyCallsPublishedService() throws Exception {
    // 配置用户实际选择的协议、挂载点和编码。
    MetConfiguration incoming = new MetConfiguration();
    incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc");
    MetConfiguration outgoing = new MetConfiguration();
    outgoing.connector("http").option("httpVersion", 2).protocol("grpc");

    try (MetContext server = incoming.buildContext(); //
         MetContext client = outgoing.buildContext()) {
        // 两端启动后，通过发布 API 注册普通 Java 接口。
        server.start();
        client.start();
        server.publisher() //
                .rsfService(Echo.class, value -> value) //
                .aliasName("grpc", "example.Echo") //
                .serialize("Json") //
                .register();
        MetBindInfo<Echo> service = client.publisher() //
                .rsfService(Echo.class) //
                .aliasName("grpc", "example.Echo") //
                .serialize("Json") //
                .register();

        // 通过公开客户端入口发起真实 RPC，断言本场景的结果。
        Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(service);
        assertEquals("hello", remote.echo("hello"));
    }
}
```

另写方法测试 Protobuf、超时或取消。不要在这个方法后面继续发布另一种服务或断言另一类失败。对于“同一端口同时提供两个协议”的场景，调用两个协议并比较监听端口属于同一个目标，可以保留在同一个方法。

链式调用的尾 `//` 用来保留必要换行，不能代替段前说明。嵌套报文构建、地址拼接、帧解码和复杂断言应先提炼局部变量；异常断言和异步等待中的被测操作仍须在回调内执行。格式化后复查可读性，不能靠增加空行或压低字符数代替逻辑整理。

## 重构验收

1. 记录拆分前的方法及验证目标，拆分后逐项对应；保留超时、释放、迟到响应、重复头部等原有断言。
2. 单测通过后运行插件全部测试和 JaCoCo 报告，查看覆盖率下降是否来自遗漏。互通在 `meteor-test` 执行，报告写清 self、provider、consumer 方向，区分直连、自动发现、适配发现和外部原生发现；官方 SDK 与语言运行时的独立进程保留启动、输出和退出证据。
3. 迁移后清理旧编译输出，避免旧包里的测试类残留造成重复执行。文档中的测试命令和例子入口也一起更新。
4. 宿主汇总结果必须属于本次实际执行。测试任务启动时先清理标准结果目录的旧 XML，再标记本轮开始；历史 run 归档保留。启动前失败时排除现存旧文件，缓存复用另行标记，不依赖 wall clock 或文件时间判断归属。分别注入启动前、报告设置后的故障，确认均不拾取旧结果；失败、中断和取消保持原状态，不计为通过。

当前宿主计划与矩阵说明见 [meteor-test 计划](../../../../document/docs/other/testing/METEOR_TEST_PLAN.md)。能力清单只描述协议和对端，不将待补场景声明为已经验收。
