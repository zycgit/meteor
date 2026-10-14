/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.client.scenario;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.admin.MetEndpointFilter;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.admin.MetServiceEndpoint;
import net.hasor.meteor.registry.admin.MetServiceMember;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.RefProtocolMountData;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** 经正式发布与注册入口核对服务选择的上下文，不验证测试 HTTP 协议的业务 RPC。 */
public class ServiceContextPublicationTest {
    private final MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();

    @Before
    public void configure() {
        this.registry.setManagementToken("secret");
        this.registry.setRefreshMillis(600000);
    }

    @Test
    public void providerWithoutContextSelectionReportsOnlyProtocolDefault() throws Exception {
        // 协议声明多个可用上下文，服务未显式选择路径。
        MetConfiguration application = this.providerConfiguration();
        try (MetContext context = application.buildContext()) {
            String id = context.publisher().rsfService(Echo.class, value -> value) //
                    .protocol("registry-http").register().getBindID();
            context.start();
            context.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);

            // 注册结果只包含默认上下文，不隐式扩展到其他声明路径。
            MetServiceAdmin service = context.getExtension(RegistryAdmin.class).getServiceAdmin(id);
            MetServiceMember member = service.providers(null, null).getItems().get(0);
            assertEquals(List.of("/default"), contextPaths(member));
            assertEquals(1, member.getAddresses().length);
        }
    }

    @Test
    public void providerExplicitContextDoesNotAddProtocolDefault() throws Exception {
        // 显式选择一个额外上下文，默认上下文不属于该服务。
        MetConfiguration application = this.providerConfiguration();
        try (MetContext context = application.buildContext()) {
            String id = context.publisher().rsfService(Echo.class, value -> value) //
                    .protocol("registry-http").contextPathByPrefix("registry-http", "/one").register().getBindID();
            context.start();
            context.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);

            // 快照与路径筛选都以服务实际选择为准。
            MetServiceAdmin service = context.getExtension(RegistryAdmin.class).getServiceAdmin(id);
            MetServiceMember member = service.providers(null, null).getItems().get(0);
            assertEquals(List.of("/one"), contextPaths(member));
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setContextPath("/default");
            assertTrue(service.providers(filter, null).getItems().isEmpty());
        }
    }

    @Test
    public void multipleContextsSurviveStorageAndFilterWithoutCollapsingEndpoints() throws Exception {
        // 同一协议、同一监听地址上发布两个上下文，并打开独立存储查询会话。
        MetConfiguration application = this.providerConfiguration();
        try (MetContext context = application.buildContext(); //
             StorageAccess storage = this.registry.openStorage()) {
            String id = context.publisher().rsfService(Echo.class, value -> value) //
                    .protocol("registry-http").contextPathByPrefix("registry-http", "/one", "/two").register().getBindID();
            context.start();
            context.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);

            // 存储保存完整端点列表，网络发现地址仍按监听地址去重。
            EntServiceRuntimeData stored = storage.getInstanceService(context.getInstanceID(), id);
            List<String> storedPaths = Arrays.stream(stored.getEndpoints()) //
                    .map(RefProtocolMountData::getContextPath).toList();
            assertEquals(List.of("/one", "/two"), storedPaths);
            assertEquals(1, stored.getAddresses().length);
            String address = stored.getAddresses()[0];
            assertEquals(address, stored.getEndpoints()[0].getPublishedAddress());
            assertEquals(address, stored.getEndpoints()[1].getPublishedAddress());

            // 按其中任一路径筛选仍返回完整成员，不截断其他已发布上下文。
            MetServiceAdmin service = context.getExtension(RegistryAdmin.class).getServiceAdmin(id);
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setProtocol("registry-http");
            filter.setContextPath("/one");
            MetServiceMember first = service.providers(filter, null).getItems().get(0);
            assertEquals(List.of("/one", "/two"), contextPaths(first));
            filter.setContextPath("/two");
            MetServiceMember second = service.providers(filter, null).getItems().get(0);
            assertEquals(List.of("/one", "/two"), contextPaths(second));
            filter.setContextPath("/default");
            assertTrue(service.providers(filter, null).getItems().isEmpty());
        }
    }

    @Test
    public void directManagementRetainsEverySelectedContext() throws Exception {
        // 通过 RSF 管理通道读取 HTTP 服务的运行快照。
        MetConfiguration application = this.providerConfiguration();
        MetConfiguration callerConfiguration = new MetConfiguration() //
                .addExtension(new RegistryConfiguration().setOnline(false));
        callerConfiguration.connector("tcp").protocol("rsf");
        try (MetContext context = application.buildContext(); //
             MetContext caller = callerConfiguration.buildContext()) {
            String id = context.publisher().rsfService(Echo.class, value -> value) //
                    .protocol("registry-http").contextPathByPrefix("registry-http", "/one", "/two").register().getBindID();
            context.start();
            caller.start();

            // 直连管理不生成注册发布地址，但保留两个实际服务上下文。
            TargetAdmin targets = caller.getExtension(TargetAdmin.class);
            MetServiceAdmin service = targets.getServiceAdminByAddress(context.bindAddress("rsf"), id);
            MetServiceMember member = service.providers(null, null).getItems().get(0);
            assertEquals(List.of("/one", "/two"), contextPaths(member));
            assertNull(member.getEndpoints()[0].getPublishedAddress());
            assertNull(member.getEndpoints()[1].getPublishedAddress());
        }
    }

    @Test
    public void consumerWithoutContextSelectionReportsOnlyProtocolDefault() throws Exception {
        // 仅主动连接的消费者使用协议默认上下文。
        MetConfiguration application = this.consumerConfiguration();
        try (MetContext context = application.buildContext()) {
            String id = context.publisher().rsfService(Echo.class) //
                    .protocol("registry-http").register().getBindID();
            context.start();
            context.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);

            // 消费者保留默认路径元信息，且不公告服务监听地址。
            MetServiceAdmin service = context.getExtension(RegistryAdmin.class).getServiceAdmin(id);
            MetServiceMember member = service.consumers(null, null).getItems().get(0);
            assertEquals(List.of("/default"), contextPaths(member));
            assertFalse(member.getEndpoints()[0].isBindEnabled());
            assertNull(member.getEndpoints()[0].getListenAddress());
            assertEquals(0, member.getAddresses().length);
        }
    }

    @Test
    public void consumerSnapshotPreservesExplicitContexts() throws Exception {
        // 消费者显式选择两个上下文；协议默认上下文不自动加入。
        MetConfiguration application = this.consumerConfiguration();
        try (MetContext context = application.buildContext(); //
             StorageAccess storage = this.registry.openStorage()) {
            String id = context.publisher().rsfService(Echo.class) //
                    .protocol("registry-http").contextPathByPrefix("registry-http", "/one", "/two").register().getBindID();
            context.start();
            context.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);

            // 存储与管理查询保留选择信息，消费者端点没有监听或发布地址。
            EntServiceRuntimeData stored = storage.getInstanceService(context.getInstanceID(), id);
            List<String> storedPaths = Arrays.stream(stored.getEndpoints()) //
                    .map(RefProtocolMountData::getContextPath).toList();
            assertEquals(List.of("/one", "/two"), storedPaths);
            MetServiceAdmin service = context.getExtension(RegistryAdmin.class).getServiceAdmin(id);
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setContextPath("/two");
            MetServiceMember member = service.consumers(filter, null).getItems().get(0);
            assertEquals(List.of("/one", "/two"), contextPaths(member));
            for (MetServiceEndpoint endpoint : member.getEndpoints()) {
                assertFalse(endpoint.isBindEnabled());
                assertNull(endpoint.getListenAddress());
                assertNull(endpoint.getPublishedAddress());
            }
            assertEquals(0, member.getAddresses().length);
            assertArrayEquals(new String[0], member.getUsedProtocols());
        }
    }

    @Test
    public void protocolWithoutContextsKeepsSingleEndpoint() throws Exception {
        // 同时配置 HTTP，但该服务只选择没有上下文概念的 RSF。
        MetConfiguration application = this.providerConfiguration();
        try (MetContext context = application.buildContext()) {
            String id = context.publisher().rsfService(Echo.class, value -> value) //
                    .protocol("rsf").register().getBindID();
            context.start();
            context.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);

            // 不使用上下文的协议仍有唯一端点，不包含未选择的 HTTP 端点。
            MetServiceAdmin service = context.getExtension(RegistryAdmin.class).getServiceAdmin(id);
            MetServiceMember member = service.providers(null, null).getItems().get(0);
            assertEquals(1, member.getEndpoints().length);
            assertEquals("rsf", member.getEndpoints()[0].getProtocolName());
            assertNull(member.getEndpoints()[0].getContextPath());
        }
    }

    private MetConfiguration providerConfiguration() {
        MetConfiguration application = new MetConfiguration().addExtension(this.registry);
        application.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        application.connector("http").bind("127.0.0.1", 0).protocol("registry-http") //
                .contextPath("/default").addContextPath("/one").addContextPath("/two");
        return application;
    }

    private MetConfiguration consumerConfiguration() {
        MetConfiguration application = new MetConfiguration().addExtension(this.registry);
        application.connector("tcp").protocol("rsf");
        application.connector("http").protocol("registry-http") //
                .contextPath("/default").addContextPath("/one").addContextPath("/two");
        return application;
    }

    private static List<String> contextPaths(MetServiceMember member) {
        return Arrays.stream(member.getEndpoints()).map(MetServiceEndpoint::getContextPath).toList();
    }
}
