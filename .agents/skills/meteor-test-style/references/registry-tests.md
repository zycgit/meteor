# 注册中心测试示例

这里记录 Meteor 注册中心已有测试的写法。API 变化时先查看所链接的当前代码；示例展示组织方式，不要求其他模块套用同样的启动流程。

## 包归属

单元测试使用被测类的真实包名，如 `net.hasor.meteor.registry.nacos`。存储提供者的必要基础契约测试保留在本模块；与多协议、官方 SDK、多语言组合的真实发现和治理场景归入 `meteor-test`。场景使用 `net.hasor.realmeteor`，模块专用测试模型使用 `net.hasor.example`；跨协议公共接口和 DTO 由 `meteor-test/peer/java/contracts/src/main/java/net/hasor/example` 提供，运行实现归 `peer/java/meteor/src/main/java`，测试使用自己持有的应用入口、契约和对端，不依赖 `met-example`。

单测直接访问同包实现，不为目录分层保留多余访问适配器。故障注入与断言夹具就近放在测试包中，不进入业务示例，也不因此修改生产 API 或可见性。

## Nacos 的分层范例

1. [NacosDiscoveryMain](../../../../meteor-integration/registry-nacos/src/test/java/net/hasor/example/registry/nacos/NacosDiscoveryMain.java)：从正式配置创建并启动两个上下文，再发布、订阅并通过真实 RPC 调用；可直接运行的用户用法放在 `net.hasor.example.registry.nacos`。
2. [NacosRegistryConfigurationTest](../../../../meteor-integration/registry-nacos/src/test/java/net/hasor/meteor/registry/nacos/NacosRegistryConfigurationTest.java)：在生产同名测试包隔离外部 SDK，验证配置传递、打开失败及资源回收，不把模拟 SDK 的成功称为真实 Nacos 互通。
3. 参考其分层、入口和资源管理方式。已有方法若同时验证多种错误条件或独立行为，仍要按主 Skill 拆分，不能照搬组合过大的测试。

## 按验证目标阅读现有测试

1. [AdminQueryTest](../../../../meteor-registry/src/test/java/net/hasor/meteor/registry/admin/AdminQueryTest.java) ：参考配置在前、多资源逐行声明的写法；其中查询、禁用、成员退出等独立目标在新测试中应分开。
2. [ConfigurationApplicationTest](../../../../meteor-registry/src/test/java/net/hasor/meteor/registry/client/ConfigurationApplicationTest.java)：通过扩展取得管理端和客户端，在当前测试内声明服务、启动上下文、拉取配置并检查应用真实状态。
3. [GovernanceSubscriptionTest](../../../../meteor-registry/src/test/java/net/hasor/meteor/registry/client/GovernanceSubscriptionTest.java)：观察存储订阅带来的自动治理变化及其影响范围。
4. [RegistryOnlineTest](../../../../meteor-registry/src/test/java/net/hasor/meteor/registry/bootstrap/RegistryOnlineTest.java)：区分扩展构建、应用启动、存储会话打开和关闭，覆盖在线及离线模式。
5. [MemoryStorageTest](../../../../meteor-registry/src/test/java/net/hasor/meteor/registry/shared/storage/MemoryStorageTest.java)：直接验证内置存储契约，避免为局部存储测试启动无关 RPC。

## 一个范围隔离场景

以下是一个只操作存储治理数据的测试方法片段。导入来自当前项目及 JUnit；无需启动应用接入链路。

```java
@Test
public void instanceOverrideLeavesServiceConfigurationUnchanged() throws Exception {
    // 准备正式扩展与管理凭证。
    MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
    registry.setManagementToken("secret");
    MetConfiguration config = new MetConfiguration().addExtension(registry);

    try (MetContext context = config.buildContext()) {
        RegistryAdmin admin = context.getExtension(RegistryAdmin.class);

        MetServiceAdmin service = admin.getServiceAdmin("echo");

        // 仅禁用某个实例上的服务，不影响服务整体与其他实例。
        MetServiceAdmin scoped = admin.getServiceAdmin("echo", "instance-a");
        scoped.disable();
        assertFalse(scoped.isEnable());
        assertTrue(service.isEnable());
        assertTrue(admin.getServiceAdmin("echo", "instance-b").isEnable());
    }
}
```

这里通过管理 API 验证实例范围的禁用，不连接业务应用；需要观察持久记录时再声明独立的 `StorageAccess` 会话。接入应用、自动订阅或 RPC 的场景应按实际生命周期声明服务并启动上下文，不能因为这个示例未启动便统一省略 `start()`。

## 长条件拆成小变量

当等待条件需要读取多个状态时，把取值和比较留在轮询回调内：

```java
RegistryTestUtils.await(() -> {
    String requestOption = server.getSettings().getRequestOptions().getOption("baseline");
    String responseOption = server.getSettings().getResponseOptions().getOption("result");
    boolean requestApplied = StringUtils.equals("managed", requestOption);
    boolean responseApplied = StringUtils.equals("yes", responseOption);
    return requestApplied && responseApplied;
});
```

此处的小变量说明条件含义，并让每轮等待重新读取状态。避免将相同长表达式仅拆到多行，也不要在回调外先算好 `requestApplied` 和 `responseApplied`。
