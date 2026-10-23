/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.registry;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.annotation.*;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;
import org.junit.Test;
import static org.junit.Assert.*;

/** 通过 bootstrap 发布服务、上报声明，再从独立存储会话或管理 API 查询。 */
public class ServiceDescriptionScenarioTest {
    @Test
    public void serviceRegistrationAutomaticallyReportsDescription() throws Exception {
        // 核对周期设为一分钟，本场景只观察发布事件触发的自动上报。
        MemoryRegistryConfiguration registry = registry();
        try (MetContext context = configuration(registry).buildContext()) {
            context.start();
            MetBindInfo<Contract> service = context.publisher().rsfService(Contract.class, value -> value).register();
            var admin = context.getExtension(RegistryAdmin.class).getServiceAdmin(service.getBindID());

            // 不调用 pushSync 或 pullSync，通过公开管理查询等待异步上报完成。
            RegistryTestUtils.await(() -> admin.descriptions().containsKey(context.getInstanceID()));
            ObjectNode description = admin.descriptions().get(context.getInstanceID());
            assertEquals("Lookup", description.path("methods").get(0).path("name").asText());
            assertEquals(0xffff_ffffL, description.path("contractId").asLong());
            assertEquals(0xffff_fffeL, description.path("methods").get(0).path("contractId").asLong());
            assertEquals(Long.valueOf(0xffff_ffffL), admin.info().getContractId());
        }
    }

    @Test
    public void directManagementRpcReturnsTheSamePureDataDescription() throws Exception {
        // 目标正常发布 RSF，调用方只提供直连管理能力，不连接注册存储。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.addExtension(new RegistryConfiguration().setOnline(false));
        outgoing.connector("tcp").protocol("rsf");
        try (MetContext server = configuration(registry).buildContext(); //
             MetContext caller = outgoing.buildContext()) {
            server.start();
            caller.start();
            MetBindInfo<Contract> service = server.publisher().rsfService(Contract.class, value -> value).register();

            // 管理 RPC 的传输对象携带纯 JSON 描述，不传递反射或协议运行对象。
            TargetAdmin target = caller.getExtension(TargetAdmin.class);
            var remote = target.getServiceAdminByAddress(server.getListenAddress("rsf"), service.getBindID());
            ObjectNode description = remote.descriptions().get(server.getInstanceID());
            assertEquals(Contract.class.getName(), description.path("javaType").asText());
            assertEquals("Lookup", description.path("methods").get(0).path("name").asText());
            assertEquals(Long.valueOf(0xffff_ffffL), remote.info().getContractId());
            assertEquals(0xffff_fffeL, description.path("methods").get(0).path("contractId").asLong());
            MetServiceInfo instanceService = target.getInstanceAdminByAddress(server.getListenAddress("rsf")) //
                    .getServiceById(service.getBindID());
            assertEquals(Long.valueOf(0xffff_ffffL), instanceService.getContractId());
        }
    }

    @Test
    public void pushReportsAnnotatedMethodParametersReturnAndDto() throws Exception {
        // 正式启动容器后再发布服务，内置注册中心用于观察完整上报正文。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration config = configuration(registry);
        try (MetContext context = config.buildContext(); //
             StorageAccess storage = registry.openStorage()) {
            context.start();
            MetBindInfo<Contract> service = context.publisher().rsfService(Contract.class, value -> value).register();

            // 主动上报只读取公共描述与已提交端点，不要求发起业务调用。
            context.getExtension(RegistryClient.class).pushSync(service.getBindID()).get(10, TimeUnit.SECONDS);
            ObjectNode description = storage.getInstanceService(context.getInstanceID(), service.getBindID()) //
                    .getDescription();
            JsonNode method = description.path("methods").get(0);
            assertEquals("Lookup", method.path("name").asText());
            assertEquals(0xffff_ffffL, description.path("contractId").asLong());
            assertEquals(0xffff_fffeL, method.path("contractId").asLong());
            assertEquals("query", method.path("parameters").get(0).path("name").asText());
            assertEquals(0, method.path("parameters").get(0).path("argIndex").asInt());
            assertEquals(10, method.path("parameters").get(0).path("fieldId").asInt());
            assertEquals("result", method.path("returns").path("name").asText());
            JsonNode dto = description.path("types").path(Query.class.getName());
            assertEquals("message", dto.path("properties").get(0).path("name").asText());
            assertEquals(7, dto.path("properties").get(0).path("fieldId").asInt());
            String connector = context.getSettings().getConnectorConfigs().iterator().next().name();
            JsonNode rsf = description.path("protocols").path("rsf").path(connector);
            assertEquals("lookup", rsf.path("methods").get(0).path("name").asText());

            // 持久目录、实例登记和管理摘要统一保留契约编号，字符串服务坐标不受影响。
            String serviceId = service.getBindID();
            EntServiceRuntimeData runtime = storage.getInstanceService(context.getInstanceID(), serviceId);
            assertEquals(Long.valueOf(0xffff_ffffL), storage.getService(serviceId).getContractId());
            assertEquals(Long.valueOf(0xffff_ffffL), runtime.getContractId());
            assertEquals("release-1.0", runtime.getVersion());
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            MetServiceInfo info = admin.getInstanceAdmin(context.getInstanceID()).getServiceById(serviceId);
            assertEquals(serviceId, info.getServiceId());
            assertEquals(Long.valueOf(0xffff_ffffL), info.getContractId());
            assertEquals("release-1.0", info.getVersion());
            MetServiceInfo catalog = admin.getClusterAdmin().services(serviceId, null).getItems().get(0);
            assertEquals(Long.valueOf(0xffff_ffffL), catalog.getContractId());
        }
    }

    @Test
    public void managementDescriptionIsDetachedFromStoredSnapshot() throws Exception {
        // 管理查询使用独立返回值，修改结果不修改注册中心或本地发布信息。
        MemoryRegistryConfiguration registry = registry();
        try (MetContext context = configuration(registry).buildContext()) {
            context.start();
            MetBindInfo<Contract> service = context.publisher().rsfService(Contract.class, value -> value).register();
            context.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);
            var admin = context.getExtension(RegistryAdmin.class).getServiceAdmin(service.getBindID());

            // 再次查询仍返回原始声明。
            ObjectNode returned = admin.descriptions().get(context.getInstanceID());
            returned.put("javaType", "changed-by-reader");
            String actual = admin.descriptions().get(context.getInstanceID()).path("javaType").asText();
            assertEquals(Contract.class.getName(), actual);
        }
    }

    @Test
    public void sameServiceKeepsDifferentInstanceContracts() throws Exception {
        // 同一服务 ID 的两个实例运行不同接口声明，不能由最后一次上报覆盖。
        MemoryRegistryConfiguration registry = registry();
        try (MetContext first = configuration(registry).buildContext(); //
             MetContext second = configuration(registry).buildContext()) {
            first.start();
            second.start();
            MetBindInfo<Contract> original = first.publisher().rsfService(Contract.class, value -> value) //
                    .name("shared").register();
            second.publisher().rsfService(NextContract.class, value -> value).name("shared").register();
            first.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);
            second.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);

            // 服务级查询按实例保留两份定义，指定实例时只返回一份。
            RegistryAdmin admin = first.getExtension(RegistryAdmin.class);
            String serviceId = original.getBindID();
            Map<String, ObjectNode> descriptions = admin.getServiceAdmin(serviceId).descriptions();
            assertEquals(2, descriptions.size());
            assertEquals(Contract.class.getName(), descriptions.get(first.getInstanceID()).path("javaType").asText());
            assertEquals(0xffff_ffffL, descriptions.get(first.getInstanceID()).path("contractId").asLong());
            assertTrue(descriptions.get(second.getInstanceID()).path("contractId").isNull());
            MetServiceInfo originalInfo = admin.getInstanceAdmin(first.getInstanceID()).getServiceById(serviceId);
            MetServiceInfo nextInfo = admin.getInstanceAdmin(second.getInstanceID()).getServiceById(serviceId);
            assertEquals(Long.valueOf(0xffff_ffffL), originalInfo.getContractId());
            assertNull(nextInfo.getContractId());
            String secondType = descriptions.get(second.getInstanceID()).path("javaType").asText();
            assertEquals(NextContract.class.getName(), secondType);
            var scoped = admin.getServiceAdmin(serviceId, first.getInstanceID());
            assertEquals(1, scoped.descriptions().size());
        }
    }

    @Test
    public void consumerReportsItsOwnDeclaration() throws Exception {
        // 只配置出站能力的消费者也上报公共声明，身份明确标记为 Consumer。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration config = new MetConfiguration().addExtension(registry);
        config.connector("tcp").protocol("rsf");
        try (MetContext context = config.buildContext(); //
             StorageAccess storage = registry.openStorage()) {
            context.start();
            MetBindInfo<Contract> service = context.publisher().rsfService(Contract.class).register();
            context.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);

            // 消费声明保留自己的方法描述，不伪装成远端提供者契约。
            ObjectNode description = storage.getInstanceService(context.getInstanceID(), service.getBindID()) //
                    .getDescription();
            assertEquals("Consumer", description.path("role").asText());
            assertEquals("Lookup", description.path("methods").get(0).path("name").asText());
            assertEquals(0xffff_ffffL, description.path("contractId").asLong());
            assertEquals(Long.valueOf(0xffff_ffffL), storage.getService(service.getBindID()).getContractId());
            assertNull(context.getListenAddress("rsf"));
        }
    }

    @Test
    public void instanceCloseRemovesDescriptionAndKeepsCatalogIdentity() throws Exception {
        // 应用关闭时完整描述跟随实例服务关系一起清理。
        MemoryRegistryConfiguration registry = registry();
        try (MetContext context = configuration(registry).buildContext(); //
             StorageAccess storage = registry.openStorage(); //
             RegistryAdmin admin = registry.buildAdmin()) {
            context.start();
            MetBindInfo<Contract> service = context.publisher().rsfService(Contract.class, value -> value).register();
            RegistryClient client = context.getExtension(RegistryClient.class);
            client.pushSync().get(10, TimeUnit.SECONDS);
            assertNotNull(storage.getInstanceService(context.getInstanceID(), service.getBindID()).getDescription());

            // 通过正式生命周期关闭应用，不保留过期的方法或 DTO。
            context.close();
            assertNull(storage.getInstanceService(context.getInstanceID(), service.getBindID()));
            assertNotNull(storage.getService(service.getBindID()));
            assertTrue(admin.getServiceAdmin(service.getBindID()).descriptions().isEmpty());
        }
    }

    @Test
    public void failedPublicationDoesNotRemainInRegistryDescription() throws Exception {
        // 已有服务正常发布，后续服务的发布事件失败时只撤销该服务。
        MemoryRegistryConfiguration registry = registry();
        try (MetContext context = configuration(registry).buildContext()) {
            context.start();
            MetBindInfo<Contract> existing = context.publisher().rsfService(Contract.class, value -> value).register();
            // 协议校验拒绝未配置的路径，公开发布入口抛异常并撤回候选服务。
            assertThrows(IllegalArgumentException.class, () -> {
                context.publisher().rsfService(NextContract.class, value -> value) //
                        .contextPathByPrefix("rsf", "/not-configured").register();
            });
            context.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            assertEquals(1, admin.getServiceAdmin(existing.getBindID()).descriptions().size());
            assertNull(context.getServiceInfo(NextContract.class));
        }
    }

    @Test
    public void undeclaredContractIdsRemainNullAfterFullPush() throws Exception {
        // 未声明编号的接口仍使用原有名称与字符串版本组成服务坐标。
        MemoryRegistryConfiguration registry = registry();
        try (MetContext context = configuration(registry).buildContext(); //
             StorageAccess storage = registry.openStorage()) {
            context.start();
            MetBindInfo<NextContract> service = context.publisher() //
                    .rsfService(NextContract.class, value -> value).register();
            context.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);

            // 不从服务坐标、方法顺序或字符串版本猜测契约编号。
            String serviceId = service.getBindID();
            EntServiceRuntimeData runtime = storage.getInstanceService(context.getInstanceID(), serviceId);
            assertNull(storage.getService(serviceId).getContractId());
            assertNull(runtime.getContractId());
            assertTrue(runtime.getDescription().path("contractId").isNull());
            assertTrue(runtime.getDescription().path("methods").get(0).path("contractId").isNull());
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            assertNull(admin.getServiceAdmin(serviceId).info().getContractId());
            assertEquals("release-1.0", admin.getServiceAdmin(serviceId).info().getVersion());
        }
    }

    @Test
    public void registryPreservesAnExplicitNumberOutsideOncProgramRange() throws Exception {
        // 公共声明的编号范围交给协议解释，注册中心只把 -1 视为未指定。
        MemoryRegistryConfiguration registry = registry();
        try (MetContext context = configuration(registry).buildContext(); //
             StorageAccess storage = registry.openStorage()) {
            context.start();
            MetBindInfo<NegativeContract> service = context.publisher() //
                    .rsfService(NegativeContract.class, value -> value).register();
            context.getExtension(RegistryClient.class).pushSync().get(10, TimeUnit.SECONDS);

            // RSF 服务没有 ONC 程序范围约束，显式负数按原值上报而不被改成 null。
            String serviceId = service.getBindID();
            EntServiceRuntimeData runtime = storage.getInstanceService(context.getInstanceID(), serviceId);
            assertEquals(Long.valueOf(-2L), runtime.getContractId());
            assertEquals(Long.valueOf(-2L), storage.getService(serviceId).getContractId());
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            assertEquals(Long.valueOf(-2L), admin.getServiceAdmin(serviceId).info().getContractId());
        }
    }

    @Test
    public void incrementalPushPreservesZeroContractIdWithoutChangingOtherServices() throws Exception {
        // 零是显式声明的程序编号，同实例另一个服务使用完整无符号 32 位编号。
        MemoryRegistryConfiguration registry = registry();
        try (MetContext context = configuration(registry).buildContext(); //
             StorageAccess storage = registry.openStorage()) {
            context.start();
            MetBindInfo<Contract> other = context.publisher() //
                    .rsfService(Contract.class, value -> value).register();
            MetBindInfo<ZeroContract> selected = context.publisher() //
                    .rsfService(ZeroContract.class, value -> value).register();
            RegistryClient client = context.getExtension(RegistryClient.class);
            client.pushSync().get(10, TimeUnit.SECONDS);

            // 只推送目标服务后，目录、运行声明和管理查询都保留零而不是变成缺省值。
            client.pushSync(selected.getBindID()).get(10, TimeUnit.SECONDS);
            String instanceId = context.getInstanceID();
            String serviceId = selected.getBindID();
            assertEquals(Long.valueOf(0), storage.getService(serviceId).getContractId());
            EntServiceRuntimeData runtime = storage.getInstanceService(instanceId, serviceId);
            assertEquals(Long.valueOf(0), runtime.getContractId());
            assertEquals(0L, runtime.getDescription().path("contractId").asLong());
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            assertEquals(Long.valueOf(0), admin.getServiceAdmin(serviceId).info().getContractId());
            Long otherId = storage.getInstanceService(instanceId, other.getBindID()).getContractId();
            assertEquals(Long.valueOf(0xffff_ffffL), otherId);
        }
    }

    private static MemoryRegistryConfiguration registry() {
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("description-tests");
        registry.setRefreshMillis(60000);
        return registry;
    }

    private static MetConfiguration configuration(MemoryRegistryConfiguration registry) {
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        return configuration;
    }

    @MetService(id = 0xffff_ffffL, version = "release-1.0")
    public interface Contract {
        @MetMethod(name = "Lookup", id = 0xffff_fffeL)
        @MetReturn(name = "result")
        Query lookup(@MetParam(name = "query", id = 10) Query query);
    }

    @MetService(version = "release-1.0")
    public interface NextContract {
        @MetMethod(name = "LookupNext")
        Query lookup(Query query);
    }

    @MetService(id = 0L)
    public interface ZeroContract {
        Query lookup(Query query);
    }

    @MetService(id = -2L)
    public interface NegativeContract {
        Query lookup(Query query);
    }

    public static class Query {
        @MetField(name = "message", id = 7)
        public String text;
    }
}
