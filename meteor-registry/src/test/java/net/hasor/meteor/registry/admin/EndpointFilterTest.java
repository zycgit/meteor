/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.rpc.InstanceData;
import net.hasor.meteor.registry.shared.rpc.ServiceData;
import net.hasor.meteor.registry.shared.rpc.ServiceEndpointData;
import net.hasor.meteor.registry.shared.rpc.ServiceMemberData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class EndpointFilterTest {

    private final MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();

    @Before
    public void configure() {
        this.storageConfiguration.setManagementToken("secret");
    }

    @Test
    public void allConditionsMustMatchOneMountAndDoNotTrimReturnedMember() throws Exception {
        // 准备管理扩展与写入端点公告的独立存储会话。
        MetConfiguration adminConfiguration = new MetConfiguration().addExtension(this.storageConfiguration);

        try (StorageAccess backend = this.storageConfiguration.openStorage(); //
             MetContext adminContext = adminConfiguration.buildContext()) {
            RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);

            // 同一个成员发布 TCP、HTTP 两个挂载。
            ServiceEndpointData tcp = endpoint("tcp-0", "tcp", "rsf", null);
            ServiceEndpointData http = endpoint("http-1", "http", "hprose", "/api");
            http.setListenAddress("hprose://0.0.0.0:8080/default");
            http.setPublishedAddress("hprose://example.com:8080/default");
            store(backend, MetServiceType.Provider, new ServiceEndpointData[] { tcp, http });
            MetServiceAdmin api = admin.getServiceAdmin("echo");

            // 传输与协议条件必须命中同一个挂载，结果保留完整成员。
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setTransport("TCP");
            filter.setProtocol("hprose");
            assertEquals(0, api.providers(filter).length);
            filter.setTransport("http");
            assertEquals(1, api.providers(filter).length);
            assertEquals(2, api.providers(filter)[0].getEndpoints().length);

            // 叠加连接器和路径条件，路径匹配区分大小写。
            filter.setConnectorName("tcp-0");
            assertEquals(0, api.providers(filter).length);
            filter.setConnectorName("http-1");
            filter.setContextPath("/API");
            assertEquals(0, api.providers(filter).length);
            filter.setContextPath("/api");
            filter.setScheme("hprose");

            // 配置地址、监听地址、发布地址均可用于匹配。
            String[] addresses = { http.getConfiguredAddress(), http.getListenAddress(), http.getPublishedAddress() };
            for (String address : addresses) {
                filter.setAddress(address);
                assertEquals(1, api.providers(filter).length);
            }
            filter.setAddress(tcp.getConfiguredAddress());
            assertEquals(0, api.providers(filter).length);
            assertEquals(0, api.consumers(null).length);

            // 订阅者使用相同筛选规则，返回的端点信息与存储隔离。
            store(backend, MetServiceType.Consumer, new ServiceEndpointData[] { tcp, http });
            filter.setAddress(http.getConfiguredAddress());
            assertEquals(1, api.consumers(filter).length);
            MetServiceMember detached = api.consumers(filter)[0];
            detached.getEndpoints()[0].setProtocol("changed");
            assertEquals("rsf", api.consumers(filter)[0].getEndpoints()[0].getProtocol());
        }
    }

    @Test
    public void instanceOnlyAndBlankFiltersIncludeMembersWithoutConfiguredEndpoints() throws Exception {
        // 准备没有端点挂载的成员。
        MetConfiguration adminConfiguration = new MetConfiguration().addExtension(this.storageConfiguration);

        try (StorageAccess backend = this.storageConfiguration.openStorage(); //
             MetContext adminContext = adminConfiguration.buildContext()) {
            RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);
            store(backend, MetServiceType.Provider, new ServiceEndpointData[0]);
            MetServiceAdmin api = admin.getServiceAdmin("echo");

            // 只有实例条件或空白传输条件时，仍能查询到成员。
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setInstanceId("one");
            filter.setTransport(" ");
            assertEquals(1, api.providers(filter).length);

            // 增加实际协议条件或不匹配的实例条件后，成员被排除。
            filter.setProtocol("rsf");
            assertEquals(0, api.providers(filter).length);
            filter.setProtocol(null);
            filter.setInstanceId("missing");
            assertEquals(0, api.providers(filter).length);
            assertEquals(1, api.providers(null).length);
        }
    }

    private static ServiceEndpointData endpoint(String connector, String transport, String protocol, String path) {
        ServiceEndpointData result = new ServiceEndpointData();
        result.setConnectorName(connector);
        result.setTransport(transport);
        result.setProtocolName(protocol);
        result.setProtocol(protocol);
        result.setScheme(protocol);
        result.setContextPath(path);
        result.setConfiguredAddress(transport + "://0.0.0.0:0/default");
        return result;
    }

    private static void store(StorageAccess backend, MetServiceType role, ServiceEndpointData[] endpoints) throws Exception {
        InstanceData instance = new InstanceData();
        instance.setInstanceId("one");
        ServiceData service = new ServiceData();
        service.setServiceId("echo");
        service.setRole(role);
        ServiceMemberData member = new ServiceMemberData();
        member.setInstance(instance);
        member.setService(service);
        member.setEndpoints(endpoints);
        new RegistryRecordFixture(instance, member).save(backend);
    }
}
