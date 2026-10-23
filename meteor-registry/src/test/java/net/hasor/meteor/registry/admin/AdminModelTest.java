/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.admin.description.MetServiceDescription;
import net.hasor.meteor.registry.shared.rpc.ServiceData;
import net.hasor.meteor.registry.shared.rpc.ServiceEndpointData;
import net.hasor.meteor.registry.shared.rpc.ServiceMemberData;
import net.hasor.meteor.registry.shared.rpc.ValidationData;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdminModelTest {
    @Test
    public void memberAddressesFollowPublishedEndpoints() throws Exception {
        // 准备重复发布地址及尚未发布的端点。
        ServiceEndpointData first = new ServiceEndpointData();
        first.setPublishedAddress("rsf://localhost:2181");
        ServiceEndpointData duplicate = new ServiceEndpointData();
        duplicate.setPublishedAddress(first.getPublishedAddress());
        ServiceEndpointData unpublished = new ServiceEndpointData();
        unpublished.setListenAddress("rsf://localhost:2182");
        ServiceMemberData stored = new ServiceMemberData();
        stored.setEndpoints(new ServiceEndpointData[] { first, duplicate, unpublished });

        // 公开地址由已发布端点去重汇总，未发布端点不产生地址。
        MetServiceMember member = RegistryJson.copy(stored, MetServiceMember.class);
        String[] addresses = member.getAddresses();
        assertEquals(1, addresses.length);
        assertTrue(StringUtils.equals(first.getPublishedAddress(), addresses[0]));

        // 端点取消发布后不残留旧汇总，序列化往返仍按当前端点派生地址。
        member.getEndpoints()[0].setPublishedAddress(null);
        member.getEndpoints()[1].setPublishedAddress(null);
        assertEquals(0, member.getAddresses().length);
        MetServiceMember restored = RegistryJson.read(RegistryJson.write(member), MetServiceMember.class);
        assertEquals(0, restored.getAddresses().length);
        String listenAddress = restored.getEndpoints()[2].getListenAddress();
        assertTrue(StringUtils.equals(unpublished.getListenAddress(), listenAddress));
    }

    @Test
    public void serviceDataJsonContainsRoleWithoutType() throws Exception {
        // 准备仅表达登记身份及角色的通信摘要。
        ServiceData service = new ServiceData();
        service.setServiceId("echo");
        service.setRole(MetServiceType.Provider);

        // JSON 使用 role 表达成员角色，不携带旧的 type 字段。
        ObjectNode json = RegistryJson.read(RegistryJson.write(service), ObjectNode.class);
        String serviceId = json.path("serviceId").asText();
        assertTrue(StringUtils.equals("echo", serviceId));
        String role = json.path("role").asText();
        assertTrue(StringUtils.equals("Provider", role));
        assertFalse(json.has("type"));
        assertFalse(json.has("javaType"));
    }

    @Test
    public void serviceInfoJsonContainsRoleWithoutType() throws Exception {
        // 准备公开管理摘要中的登记身份及角色。
        MetServiceInfo service = new MetServiceInfo();
        service.setServiceId("echo");
        service.setRole(MetServiceType.Consumer);

        // 公开 JSON 同样使用 role，不携带旧的 type 字段。
        ObjectNode json = RegistryJson.read(RegistryJson.write(service), ObjectNode.class);
        String serviceId = json.path("serviceId").asText();
        assertTrue(StringUtils.equals("echo", serviceId));
        String role = json.path("role").asText();
        assertTrue(StringUtils.equals("Consumer", role));
        assertFalse(json.has("type"));
        assertFalse(json.has("javaType"));
    }

    @Test
    public void serviceMemberDataJsonContainsEndpointsWithoutDerivedSummaries() throws Exception {
        // 准备成员当前端点，地址信息仅保存在端点内。
        ServiceEndpointData endpoint = new ServiceEndpointData();
        endpoint.setPublishedAddress("rsf://localhost:2181");
        ServiceMemberData member = new ServiceMemberData();
        member.setEndpoints(new ServiceEndpointData[] { endpoint });

        // 通信 JSON 保留端点，不再生成地址及有效配置汇总字段。
        ObjectNode json = RegistryJson.read(RegistryJson.write(member), ObjectNode.class);
        assertEquals(1, json.path("endpoints").size());
        String publishedAddress = json.path("endpoints").get(0).path("publishedAddress").asText();
        assertTrue(StringUtils.equals(endpoint.getPublishedAddress(), publishedAddress));
        assertFalse(json.has("addresses"));
        assertFalse(json.has("effectiveConfiguration"));
    }

    @Test
    public void serviceEndpointDataJsonUsesProtocolName() throws Exception {
        // 准备通信端点的协议挂载名称。
        ServiceEndpointData endpoint = new ServiceEndpointData();
        endpoint.setProtocolName("custom-rsf");

        // JSON 只使用 protocolName，不生成旧的 protocol 别名。
        ObjectNode json = RegistryJson.read(RegistryJson.write(endpoint), ObjectNode.class);
        String protocolName = json.path("protocolName").asText();
        assertTrue(StringUtils.equals("custom-rsf", protocolName));
        assertFalse(json.has("protocol"));
    }

    @Test
    public void serviceEndpointJsonUsesProtocolName() throws Exception {
        // 准备公开管理端点的协议挂载名称。
        MetServiceEndpoint endpoint = new MetServiceEndpoint();
        endpoint.setProtocolName("custom-rsf");

        // 公开 JSON 同样只使用 protocolName。
        ObjectNode json = RegistryJson.read(RegistryJson.write(endpoint), ObjectNode.class);
        String protocolName = json.path("protocolName").asText();
        assertTrue(StringUtils.equals("custom-rsf", protocolName));
        assertFalse(json.has("protocol"));
    }

    @Test
    public void serviceDescriptionJsonRetainsJavaTypeWithoutRole() throws Exception {
        // 准备完整描述中的 Java 接口类型。
        MetServiceDescription description = new MetServiceDescription();
        description.setJavaType("example.EchoService");

        // 完整描述保留 javaType，成员角色不属于声明内容。
        ObjectNode json = RegistryJson.read(RegistryJson.write(description), ObjectNode.class);
        String javaType = json.path("javaType").asText();
        assertTrue(StringUtils.equals("example.EchoService", javaType));
        assertFalse(json.has("role"));
    }

    @Test
    public void validationFollowsErrorsAcrossConversionAndRoundTrip() throws Exception {
        // 输入声明成功但含错误信息，结果应以错误列表为准。
        String inconsistentResult = "{\"valid\":true,\"errors\":[\"Invalid rule\"]}";
        ValidationData stored = RegistryJson.read(inconsistentResult, ValidationData.class);
        assertFalse(stored.isValid());

        // 转换为管理模型后保留相同的校验结果。
        MetValidationResult result = RegistryJson.copy(stored, MetValidationResult.class);
        assertFalse(result.isValid());
        assertArrayEquals(stored.getErrors(), result.getErrors());

        // 清空错误后恢复有效状态，序列化往返保持一致。
        result.setErrors(new String[0]);
        assertTrue(result.isValid());
        MetValidationResult restored = RegistryJson.read(RegistryJson.write(result), MetValidationResult.class);
        assertTrue(restored.isValid());
        assertEquals(0, restored.getErrors().length);
    }
}
