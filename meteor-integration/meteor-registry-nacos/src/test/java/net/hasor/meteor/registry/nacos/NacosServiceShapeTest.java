/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.nacos;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.jupiter.api.Test;
import static net.hasor.meteor.registry.nacos.NacosTestData.instance;
import static net.hasor.meteor.registry.nacos.NacosTestData.service;
import static org.junit.jupiter.api.Assertions.*;

public class NacosServiceShapeTest {
    @Test
    public void storedCatalogAndMembershipHaveNoTopLevelType() throws Exception {
        // Java 定位信息只属于实例自己的完整服务描述。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("node"));
            EntServiceRuntimeData registration = service("echo");
            ObjectNode description = JsonNodeFactory.instance.objectNode();
            description.put("javaType", "example.EchoService");
            registration.setDescription(description);
            storage.registerService("node", registration);

            // 直接解析写给 Config 和 Naming SDK 的 JSON，顶层都不出现 type。
            ObjectNode catalog = RegistryJson.read(sdk.documents.get("services.echo"), ObjectNode.class);
            String membershipJson = sdk.nodes.get("services.echo.providers").get("node").getMetadata().get("body");
            ObjectNode membership = RegistryJson.read(membershipJson, ObjectNode.class);
            String catalogServiceId = catalog.path("serviceId").textValue();
            assertEquals("echo", catalogServiceId);
            assertFalse(catalog.has("type"));
            assertFalse(catalog.has("javaType"));
            assertFalse(catalog.has("description"));
            assertFalse(membership.has("type"));
            assertFalse(membership.has("javaType"));
            JsonNode storedDescription = membership.get("description");
            assertEquals(description, storedDescription);
            assertFalse(storedDescription.has("role"));

            // 正式查询返回的运行登记序列化后同样没有 type，角色仍然明确为提供者。
            EntServiceRuntimeData registered = storage.getInstanceService("node", "echo");
            String runtimeJson = RegistryJson.write(registered);
            ObjectNode runtime = RegistryJson.read(runtimeJson, ObjectNode.class);
            String runtimeRole = runtime.path("role").textValue();
            assertFalse(runtime.has("type"));
            assertFalse(runtime.path("description").has("role"));
            assertEquals("Provider", runtimeRole);
        }
    }

    @Test
    public void storedEndpointsUseProtocolNameForQueriesAndSummary() throws Exception {
        // 同一服务的两个连接器都发布 rsf 协议，仅设置 protocolName。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("node"));
            EntServiceRuntimeData registration = service("echo");
            RefProtocolMountData primary = new RefProtocolMountData();
            primary.setConnectorName("primary");
            primary.setProtocolName("rsf");
            primary.setPublishedAddress("rsf://127.0.0.1:8000");
            RefProtocolMountData backup = new RefProtocolMountData();
            backup.setConnectorName("backup");
            backup.setProtocolName("rsf");
            backup.setPublishedAddress("rsf://127.0.0.1:8001");
            registration.setEndpoints(new RefProtocolMountData[] { primary, backup });
            storage.registerService("node", registration);

            // Naming 正文保留两个连接器，每个端点只用 protocolName 表达协议名称。
            String membershipJson = sdk.nodes.get("services.echo.providers").get("node").getMetadata().get("body");
            ObjectNode membership = RegistryJson.read(membershipJson, ObjectNode.class);
            JsonNode endpoints = membership.get("endpoints");
            assertEquals(2, endpoints.size());
            String primaryConnectorName = endpoints.get(0).path("connectorName").textValue();
            String backupConnectorName = endpoints.get(1).path("connectorName").textValue();
            assertEquals("primary", primaryConnectorName);
            assertEquals("backup", backupConnectorName);
            for (JsonNode endpoint : endpoints) {
                String protocolName = endpoint.path("protocolName").textValue();
                assertEquals("rsf", protocolName);
                assertFalse(endpoint.has("protocol"));
            }

            // 查询条件仍叫 protocol，成员目录、实例目录和服务摘要都识别 rsf。
            DirectoryQuery query = new DirectoryQuery();
            query.setProtocol("rsf");
            RefServiceRelationData member = storage.members("echo", MetServiceType.Provider, query).getItems().get(0);
            assertEquals("echo", member.getService().getServiceId());
            EntServiceRuntimeData instanceService = storage.instanceServices("node", query).getItems().get(0);
            assertEquals("echo", instanceService.getServiceId());
            ServiceSummary summary = storage.serviceSummaries(List.of("echo")).get("echo");
            assertArrayEquals(new String[] { "rsf" }, summary.getProtocols());
        }
    }

    @Test
    public void javaTypesStayWithTheirOwnInstance() throws Exception {
        // 同一个服务 ID 的两个实例分别上报不同的 Java 类型。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            storage.registerInstance(instance("two"));
            EntServiceRuntimeData first = service("echo");
            ObjectNode firstDescription = JsonNodeFactory.instance.objectNode();
            firstDescription.put("javaType", "example.FirstEchoService");
            first.setDescription(firstDescription);
            storage.registerService("one", first);
            EntServiceRuntimeData second = service("echo");
            ObjectNode secondDescription = JsonNodeFactory.instance.objectNode();
            secondDescription.put("javaType", "example.SecondEchoService");
            second.setDescription(secondDescription);
            storage.registerService("two", second);

            // 实际 Naming JSON 和重新组装的成员都保留本实例的类型。
            String firstJson = sdk.nodes.get("services.echo.providers").get("one").getMetadata().get("body");
            String secondJson = sdk.nodes.get("services.echo.providers").get("two").getMetadata().get("body");
            ObjectNode firstMembership = RegistryJson.read(firstJson, ObjectNode.class);
            ObjectNode secondMembership = RegistryJson.read(secondJson, ObjectNode.class);
            assertEquals(firstDescription, firstMembership.get("description"));
            assertEquals(secondDescription, secondMembership.get("description"));
            ObjectNode instanceDescription = storage.getInstanceService("one", "echo").getDescription();
            assertEquals(firstDescription, instanceDescription);
            ObjectNode memberDescription = storage.getMember("echo", MetServiceType.Provider, "two") //
                    .getService().getDescription();
            assertEquals(secondDescription, memberDescription);
        }
    }

    @Test
    public void missingJavaTypeIsNotBackfilledFromAnotherInstance() throws Exception {
        // 一个实例省略 javaType，随后另一个实例为同一服务 ID 上报 Java 类型。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("missing"));
            storage.registerInstance(instance("declared"));
            EntServiceRuntimeData missing = service("echo");
            ObjectNode missingDescription = JsonNodeFactory.instance.objectNode();
            missingDescription.put("name", "Echo");
            missing.setDescription(missingDescription);
            storage.registerService("missing", missing);
            EntServiceRuntimeData declared = service("echo");
            ObjectNode declaredDescription = JsonNodeFactory.instance.objectNode();
            declaredDescription.put("javaType", "example.EchoService");
            declared.setDescription(declaredDescription);
            storage.registerService("declared", declared);

            // 缺省实例的真实正文和两种读取方向都不补入其他实例的 javaType。
            String membershipJson = sdk.nodes.get("services.echo.providers").get("missing").getMetadata().get("body");
            ObjectNode membership = RegistryJson.read(membershipJson, ObjectNode.class);
            assertEquals(missingDescription, membership.get("description"));
            ObjectNode instanceDescription = storage.getInstanceService("missing", "echo").getDescription();
            ObjectNode memberDescription = storage.getMember("echo", MetServiceType.Provider, "missing") //
                    .getService().getDescription();
            assertFalse(instanceDescription.has("javaType"));
            assertFalse(memberDescription.has("javaType"));
            assertEquals(missingDescription, instanceDescription);
            assertEquals(missingDescription, memberDescription);
        }
    }
}
