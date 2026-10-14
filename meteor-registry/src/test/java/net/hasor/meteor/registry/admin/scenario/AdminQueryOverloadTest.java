/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.scenario;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntApplicationCatalogData;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.RefProtocolMountData;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** 集群、实例和服务成员目录使用有界分页，并保留各自的查询范围与组合筛选。 */
public class AdminQueryOverloadTest {
    private final MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();

    @Before
    public void configure() {
        this.registry.setManagementToken("secret");
    }

    @Test
    public void applicationsCanBeReadAcrossDefaultSizedPages() throws Exception {
        // 保存超过默认单页数量的独立应用，不需要登记实例。
        MetConfiguration configuration = new MetConfiguration().addExtension(this.registry);
        try (StorageAccess storage = this.registry.openStorage(); //
             MetContext context = configuration.buildContext()) {
            Set<String> expected = new HashSet<>();
            for (int index = 0; index < 105; index++) {
                EntApplicationCatalogData application = new EntApplicationCatalogData();
                application.setApplicationName("app-" + index);
                storage.saveApplication(application);
                expected.add(application.getApplicationName());
            }

            // 未指定条件时返回默认大小的第一页，并提供后续查询游标。
            MetClusterAdmin admin = context.getExtension(RegistryAdmin.class).getClusterAdmin();
            MetPage<String> first = admin.applications(null);
            assertEquals(100, first.getItems().size());
            assertNotNull(first.getNextCursor());

            // 通过游标取得剩余应用，完整目录没有遗漏或重复。
            MetDirectoryQuery query = new MetDirectoryQuery();
            query.setCursor(first.getNextCursor());
            MetPage<String> last = admin.applications(query);
            assertEquals(5, last.getItems().size());
            assertNull(last.getNextCursor());
            List<String> applications = new ArrayList<>(first.getItems());
            applications.addAll(last.getItems());
            assertEquals(105, applications.size());
            assertEquals(expected, new HashSet<>(applications));
        }
    }

    @Test
    public void instancePagesRetainApplicationScopeAcrossDefaultSizedPages() throws Exception {
        // 为目标应用登记超过一页的实例，并登记一个无关应用实例。
        MetConfiguration configuration = new MetConfiguration().addExtension(this.registry);
        try (StorageAccess storage = this.registry.openStorage(); //
             MetContext context = configuration.buildContext()) {
            Set<String> expected = new HashSet<>();
            for (int index = 0; index < 105; index++) {
                String instanceId = "node-" + index;
                storage.registerInstance(instance(instanceId, "orders", "blue"));
                expected.add(instanceId);
            }
            storage.registerInstance(instance("other", "inventory", "blue"));

            // 遍历目标应用的全部分页，每一页均保持应用范围。
            MetClusterAdmin admin = context.getExtension(RegistryAdmin.class).getClusterAdmin();
            MetDirectoryQuery query = new MetDirectoryQuery();
            Set<String> found = new HashSet<>();
            int pageCount = 0;
            do {
                MetPage<MetInstanceInfo> page = admin.instances("orders", query);
                assertFalse(page.getItems().isEmpty());
                assertTrue(page.getItems().size() <= query.getLimit());
                for (MetInstanceInfo instance : page.getItems()) {
                    assertEquals("orders", instance.getApplicationName());
                    assertTrue(found.add(instance.getInstanceId()));
                }
                query.setCursor(page.getNextCursor());
                pageCount++;
            } while (query.getCursor() != null);
            assertEquals(2, pageCount);
            assertEquals(expected, found);

            // 不指定应用时范围扩展到整个集群，包含无关应用的实例。
            query.setLimit(200);
            MetPage<MetInstanceInfo> cluster = admin.instances(null, query);
            assertEquals(106, cluster.getItems().size());
            assertNull(cluster.getNextCursor());
        }
    }

    @Test
    public void servicePagesRetainFilterAcrossDefaultSizedPages() throws Exception {
        // 服务声明独立持久化，准备超过一页的匹配项和一个无关服务。
        MetConfiguration configuration = new MetConfiguration().addExtension(this.registry);
        try (StorageAccess storage = this.registry.openStorage(); //
             MetContext context = configuration.buildContext()) {
            Set<String> expected = new HashSet<>();
            for (int index = 0; index < 105; index++) {
                String serviceId = "echo-" + index;
                storage.saveService(service(serviceId, MetServiceType.Provider, "public"));
                expected.add(serviceId);
            }
            storage.saveService(service("unrelated", MetServiceType.Provider, "public"));

            // 逐页读取匹配声明，跨页后不遗漏、重复或混入无关服务。
            MetClusterAdmin admin = context.getExtension(RegistryAdmin.class).getClusterAdmin();
            MetDirectoryQuery query = new MetDirectoryQuery();
            Set<String> found = new HashSet<>();
            int pageCount = 0;
            do {
                MetPage<MetServiceInfo> page = admin.services("echo-", query);
                assertFalse(page.getItems().isEmpty());
                assertTrue(page.getItems().size() <= query.getLimit());
                for (MetServiceInfo service : page.getItems()) {
                    assertTrue(found.add(service.getServiceId()));
                }
                query.setCursor(page.getNextCursor());
                pageCount++;
            } while (query.getCursor() != null);
            assertEquals(2, pageCount);
            assertEquals(expected, found);

            // 未指定筛选条件时，集群目录仍包含全部持久化服务。
            query.setLimit(200);
            MetPage<MetServiceInfo> cluster = admin.services(null, query);
            assertEquals(106, cluster.getItems().size());
            assertNull(cluster.getNextCursor());
        }
    }

    @Test
    public void instanceServiceListCanBeReadAcrossDefaultSizedPages() throws Exception {
        // 同一实例登记超过默认单页数量的服务。
        MetConfiguration configuration = new MetConfiguration().addExtension(this.registry);
        try (StorageAccess storage = this.registry.openStorage(); //
             MetContext context = configuration.buildContext()) {
            storage.registerInstance(instance("node", "orders", "blue"));
            Set<String> expected = new HashSet<>();
            for (int index = 0; index < 105; index++) {
                String serviceId = "echo-" + index;
                storage.registerService("node", service(serviceId, MetServiceType.Provider, "public"));
                expected.add(serviceId);
            }
            storage.registerInstance(instance("other", "orders", "blue"));
            storage.registerService("other", service("unrelated", MetServiceType.Provider, "public"));

            // 未指定条件只读取默认大小的第一页，实例范围不包含其他实例的服务。
            MetInstanceAdmin admin = context.getExtension(RegistryAdmin.class).getInstanceAdmin("node");
            MetPage<MetServiceInfo> first = admin.getServiceList(null);
            assertEquals(100, first.getItems().size());
            assertNotNull(first.getNextCursor());

            // 继续读取剩余页，确保全部服务没有遗漏或重复。
            MetDirectoryQuery query = new MetDirectoryQuery();
            query.setCursor(first.getNextCursor());
            MetPage<MetServiceInfo> last = admin.getServiceList(query);
            assertEquals(5, last.getItems().size());
            assertNull(last.getNextCursor());
            List<MetServiceInfo> services = new ArrayList<>(first.getItems());
            services.addAll(last.getItems());
            Set<String> found = new HashSet<>();
            for (MetServiceInfo service : services) {
                assertTrue(found.add(service.getServiceId()));
            }
            assertEquals(expected, found);

            // 精确查询仍然直接定位当前实例下的服务。
            assertEquals("echo-104", admin.getServiceById("echo-104").getServiceId());
            assertThrows(IllegalArgumentException.class, () -> admin.getServiceById("missing"));
        }
    }

    @Test
    public void providersCanBeReadAcrossDefaultSizedPages() throws Exception {
        // 每个实例登记相同服务，匹配成员数量超过默认单页大小。
        MetConfiguration configuration = new MetConfiguration().addExtension(this.registry);
        try (StorageAccess storage = this.registry.openStorage(); //
             MetContext context = configuration.buildContext()) {
            Set<String> expected = new HashSet<>();
            for (int index = 0; index < 105; index++) {
                String id = "node-" + index;
                storage.registerInstance(instance(id, "orders", "blue"));
                storage.registerService(id, service("echo", MetServiceType.Provider, "public"));
                expected.add(id);
            }
            storage.registerInstance(instance("other", "orders", "blue"));
            storage.registerService("other", service("echo", MetServiceType.Provider, "private"));

            // 端点筛选在分页之前执行；未指定分页条件时返回默认大小的一页。
            MetServiceAdmin admin = context.getExtension(RegistryAdmin.class).getServiceAdmin("echo");
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setConnectorName("public");
            MetPage<MetServiceMember> first = admin.providers(filter, null);
            assertEquals(100, first.getItems().size());
            assertNotNull(first.getNextCursor());

            // 携带相同筛选条件读取下一页，成员没有遗漏或重复。
            MetDirectoryQuery query = new MetDirectoryQuery();
            query.setCursor(first.getNextCursor());
            MetPage<MetServiceMember> last = admin.providers(filter, query);
            assertEquals(5, last.getItems().size());
            assertNull(last.getNextCursor());
            List<MetServiceMember> members = new ArrayList<>(first.getItems());
            members.addAll(last.getItems());
            Set<String> found = new HashSet<>();
            for (MetServiceMember member : members) {
                assertEquals("public", member.getEndpoints()[0].getConnectorName());
                assertTrue(found.add(member.getInstance().getInstanceId()));
            }
            assertEquals(expected, found);
        }
    }

    @Test
    public void consumersCanBeReadAcrossDefaultSizedPages() throws Exception {
        // 每个消费实例登记相同服务，匹配成员数量超过默认单页大小。
        MetConfiguration configuration = new MetConfiguration().addExtension(this.registry);
        try (StorageAccess storage = this.registry.openStorage(); //
             MetContext context = configuration.buildContext()) {
            Set<String> expected = new HashSet<>();
            for (int index = 0; index < 105; index++) {
                String id = "node-" + index;
                storage.registerInstance(instance(id, "orders", "blue"));
                storage.registerService(id, service("echo", MetServiceType.Consumer, "public"));
                expected.add(id);
            }
            storage.registerInstance(instance("other", "orders", "blue"));
            storage.registerService("other", service("echo", MetServiceType.Consumer, "private"));

            // 消费者端点同样先筛选，再按默认页大小返回结果。
            MetServiceAdmin admin = context.getExtension(RegistryAdmin.class).getServiceAdmin("echo");
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setConnectorName("public");
            MetPage<MetServiceMember> first = admin.consumers(filter, null);
            assertEquals(100, first.getItems().size());
            assertNotNull(first.getNextCursor());

            // 游标继续读取剩余消费成员，不能漏掉第一页之后的结果。
            MetDirectoryQuery query = new MetDirectoryQuery();
            query.setCursor(first.getNextCursor());
            MetPage<MetServiceMember> last = admin.consumers(filter, query);
            assertEquals(5, last.getItems().size());
            assertNull(last.getNextCursor());
            List<MetServiceMember> members = new ArrayList<>(first.getItems());
            members.addAll(last.getItems());
            Set<String> found = new HashSet<>();
            for (MetServiceMember member : members) {
                assertEquals("public", member.getEndpoints()[0].getConnectorName());
                assertTrue(found.add(member.getInstance().getInstanceId()));
            }
            assertEquals(expected, found);
        }
    }

    @Test
    public void pagedServiceQueryUsesTheExistingFilterArgument() throws Exception {
        // 保存两个匹配服务和一个无关服务。
        MetConfiguration configuration = new MetConfiguration().addExtension(this.registry);
        try (StorageAccess storage = this.registry.openStorage(); //
             MetContext context = configuration.buildContext()) {
            storage.saveService(service("echo-a", MetServiceType.Provider, "public"));
            storage.saveService(service("echo-b", MetServiceType.Provider, "public"));
            storage.saveService(service("unrelated", MetServiceType.Provider, "public"));
            MetClusterAdmin admin = context.getExtension(RegistryAdmin.class).getClusterAdmin();

            // services 原有 filter 参数优先，分页配置中的 search 不覆盖它。
            MetDirectoryQuery query = new MetDirectoryQuery();
            query.setField("id");
            query.setSearch("unrelated");
            query.setLimit(1);
            Set<String> found = new HashSet<>();
            do {
                MetPage<MetServiceInfo> page = admin.services("echo-", query);
                assertEquals(1, page.getItems().size());
                assertTrue(found.add(page.getItems().get(0).getServiceId()));
                query.setCursor(page.getNextCursor());
            } while (query.getCursor() != null);

            assertEquals(Set.of("echo-a", "echo-b"), found);
            assertEquals("unrelated", query.getSearch());
            query.setLimit(10);
            assertEquals(3, admin.services(null, query).getItems().size());
        }
    }

    @Test
    public void pagedInstanceServiceListKeepsTheTargetInstanceAndRoleFilter() throws Exception {
        // 目标实例提供两个服务、订阅另一个服务，无关实例也提供服务。
        MetConfiguration configuration = new MetConfiguration().addExtension(this.registry);
        try (StorageAccess storage = this.registry.openStorage(); //
             MetContext context = configuration.buildContext()) {
            storage.registerInstance(instance("target", "orders", "blue"));
            storage.registerInstance(instance("other", "orders", "blue"));
            storage.registerService("target", service("echo-a", MetServiceType.Provider, "public"));
            storage.registerService("target", service("echo-b", MetServiceType.Provider, "public"));
            storage.registerService("target", service("subscribed", MetServiceType.Consumer, "public"));
            storage.registerService("other", service("unrelated", MetServiceType.Provider, "public"));

            // 按角色分页，范围始终是创建管理对象时指定的实例。
            MetInstanceAdmin admin = context.getExtension(RegistryAdmin.class).getInstanceAdmin("target");
            MetDirectoryQuery query = new MetDirectoryQuery();
            query.setField("role");
            query.setSearch("Provider");
            query.setLimit(1);
            Set<String> found = new HashSet<>();
            do {
                MetPage<MetServiceInfo> page = admin.getServiceList(query);
                assertEquals(1, page.getItems().size());
                MetServiceInfo item = page.getItems().get(0);
                assertEquals(MetServiceType.Provider, item.getRole());
                assertTrue(found.add(item.getServiceId()));
                query.setCursor(page.getNextCursor());
            } while (query.getCursor() != null);

            assertEquals(Set.of("echo-a", "echo-b"), found);
        }
    }

    @Test
    public void pagedProvidersCombineEndpointAndDirectoryFiltersBeforePaging() throws Exception {
        // 有效提供者之间插入端点不匹配项，并放入其他单元的同端点实例。
        MetConfiguration configuration = new MetConfiguration().addExtension(this.registry);
        try (StorageAccess storage = this.registry.openStorage(); //
             MetContext context = configuration.buildContext()) {
            for (int index = 0; index < 6; index++) {
                String id = "node-" + index;
                String connector = "private";
                if (index % 2 == 1) {
                    connector = "public";
                }
                storage.registerInstance(instance(id, "orders", "blue"));
                storage.registerService(id, service("echo", MetServiceType.Provider, connector));
            }
            storage.registerInstance(instance("other-unit", "orders", "green"));
            storage.registerService("other-unit", service("echo", MetServiceType.Provider, "public"));

            // 每页一个匹配成员；稀疏的端点匹配不能导致游标漏项或重复。
            MetServiceAdmin admin = context.getExtension(RegistryAdmin.class).getServiceAdmin("echo");
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setConnectorName("public");
            MetDirectoryQuery query = new MetDirectoryQuery();
            query.setProtocol("rsf");
            query.setUnitName("blue");
            query.setLimit(1);
            Set<String> found = new HashSet<>();
            do {
                MetPage<MetServiceMember> page = admin.providers(filter, query);
                assertEquals(1, page.getItems().size());
                MetServiceMember item = page.getItems().get(0);
                assertEquals("blue", item.getInstance().getUnitName());
                assertEquals("public", item.getEndpoints()[0].getConnectorName());
                assertTrue(found.add(item.getInstance().getInstanceId()));
                query.setCursor(page.getNextCursor());
            } while (query.getCursor() != null);

            assertEquals(Set.of("node-1", "node-3", "node-5"), found);
        }
    }

    @Test
    public void pagedConsumersCombineEndpointAndDirectoryFiltersBeforePaging() throws Exception {
        // 消费实例同样交错登记匹配和不匹配的连接器。
        MetConfiguration configuration = new MetConfiguration().addExtension(this.registry);
        try (StorageAccess storage = this.registry.openStorage(); //
             MetContext context = configuration.buildContext()) {
            for (int index = 0; index < 6; index++) {
                String id = "node-" + index;
                String connector = "private";
                if (index % 2 == 1) {
                    connector = "public";
                }
                storage.registerInstance(instance(id, "orders", "blue"));
                storage.registerService(id, service("echo", MetServiceType.Consumer, connector));
            }
            storage.registerInstance(instance("other-unit", "orders", "green"));
            storage.registerService("other-unit", service("echo", MetServiceType.Consumer, "public"));

            // 协议条件使用消费者实际选择的协议，再与端点条件共同限制结果。
            MetServiceAdmin admin = context.getExtension(RegistryAdmin.class).getServiceAdmin("echo");
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setConnectorName("public");
            MetDirectoryQuery query = new MetDirectoryQuery();
            query.setProtocol("rsf");
            query.setUnitName("blue");
            query.setLimit(1);
            Set<String> found = new HashSet<>();
            do {
                MetPage<MetServiceMember> page = admin.consumers(filter, query);
                assertEquals(1, page.getItems().size());
                MetServiceMember item = page.getItems().get(0);
                assertEquals("blue", item.getInstance().getUnitName());
                assertEquals("public", item.getEndpoints()[0].getConnectorName());
                assertTrue(found.add(item.getInstance().getInstanceId()));
                query.setCursor(page.getNextCursor());
            } while (query.getCursor() != null);

            assertEquals(Set.of("node-1", "node-3", "node-5"), found);
        }
    }

    private static EntInstanceRuntimeData instance(String id, String application, String unit) {
        EntInstanceRuntimeData result = new EntInstanceRuntimeData();
        result.setInstanceId(id);
        result.setApplicationName(application);
        result.setUnitName(unit);
        return result;
    }

    private static EntServiceRuntimeData service(String id, MetServiceType role, String connector) {
        RefProtocolMountData endpoint = new RefProtocolMountData();
        endpoint.setConnectorName(connector);
        endpoint.setTransport("tcp");
        endpoint.setProtocolName("rsf");
        endpoint.setProtocol("rsf");

        EntServiceRuntimeData result = new EntServiceRuntimeData();
        result.setServiceId(id);
        result.setRole(role);
        result.setEndpoints(new RefProtocolMountData[] { endpoint });
        if (role == MetServiceType.Consumer) {
            result.setUsedProtocols(new String[] { "rsf" });
        }
        return result;
    }
}
