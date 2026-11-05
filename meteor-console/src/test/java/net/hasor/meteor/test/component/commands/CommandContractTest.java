/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test.component.commands;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.meteor.console.component.commands.ChangeResult;
import net.hasor.meteor.console.component.commands.CommandType;
import net.hasor.meteor.console.component.commands.dto.directory.ServicesRequest;
import net.hasor.meteor.console.component.commands.dto.directory.ServicesResult;
import net.hasor.meteor.console.component.commands.dto.metadata.MetadataResult;
import net.hasor.meteor.console.component.commands.dto.metadata.PutMetadataRequest;
import net.hasor.meteor.console.component.commands.dto.rule.UpdateRuleRequest;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CommandContractTest {
    @Test
    public void ruleUpdateRoundTripKeepsTargetAndXmlWithoutSerializingCommand() throws Exception {
        // 规则正文作为数据传递，命令类型由请求类确定，不增加可伪造的 JSON 字段。
        ManagementTarget target = new ManagementTarget();
        target.setScope("service");
        target.setServiceId("orders:OrderService:1");
        String rule = "<controlSet><flow name=\"订单 &amp; 查询\"/></controlSet>";
        UpdateRuleRequest request = new UpdateRuleRequest(target, MetRuleType.FLOW_CONTROL, rule);

        byte[] requestJson = JsonUtils.writeValueAsBytes(request);
        UpdateRuleRequest restored = JsonUtils.readValue(requestJson, new TypeReference<>() {
        });
        JsonNode encoded = JsonUtils.readTree(requestJson);
        assertFalse(encoded.has("command"));
        assertEquals("service", encoded.at("/target/scope").textValue());
        assertEquals("orders:OrderService:1", encoded.at("/target/serviceId").textValue());
        assertEquals("FLOW_CONTROL", encoded.path("ruleType").textValue());
        assertEquals(rule, encoded.path("rule").textValue());
        assertEquals(CommandType.UPDATE_RULE, restored.getCommand());
        assertEquals(encoded, JsonUtils.valueToTree(restored));

        // 修改结果使用明确的成功标记与影响数量，往返后保持固定形状。
        byte[] resultJson = JsonUtils.writeValueAsBytes(new ChangeResult(true, 2));
        ChangeResult result = JsonUtils.readValue(resultJson, new TypeReference<>() {
        });
        JsonNode expected = JsonUtils.readTree("{\"success\":true,\"affected\":2}");
        assertEquals(expected, JsonUtils.readTree(resultJson));
        assertEquals(expected, JsonUtils.valueToTree(result));
    }

    @Test
    public void metadataRoundTripKeepsNestedValuesAndNativeTypes() throws Exception {
        // 元信息允许嵌套对象和原生 JSON 类型，不将布尔值、数字或列表转换成字符串。
        ManagementTarget target = new ManagementTarget();
        target.setScope("cluster");
        Map<String, Object> values = Map.of("owner", "平台", "enabled", true, "priority", 7, "routing", Map.of("weights", List.of(1, 2), "region", "east"));
        PutMetadataRequest request = new PutMetadataRequest(target, values);

        byte[] requestJson = JsonUtils.writeValueAsBytes(request);
        PutMetadataRequest restored = JsonUtils.readValue(requestJson, new TypeReference<>() {
        });
        JsonNode expected = JsonUtils.readTree("""
                {"values":{"owner":"平台","enabled":true,"priority":7,
                  "routing":{"weights":[1,2],"region":"east"}}}
                """);
        assertEquals(expected.path("values"), JsonUtils.valueToTree(restored.getValues()));
        assertEquals(values, restored.getValues());

        // 读取元信息的结果以 values 为唯一外层字段，避免与目标或命令字段混合。
        byte[] resultJson = JsonUtils.writeValueAsBytes(new MetadataResult(restored.getValues()));
        MetadataResult result = JsonUtils.readValue(resultJson, new TypeReference<>() {
        });
        assertEquals(expected, JsonUtils.readTree(resultJson));
        assertEquals(expected, JsonUtils.valueToTree(result));
    }

    @Test
    public void servicesRoundTripKeepsDirectoryQueryAndTypedPageEntries() throws Exception {
        // 目录请求保留筛选、协议、单元和游标条件，反序列化后仍能生成相同查询。
        ManagementTarget target = new ManagementTarget();
        target.setFilter("order");
        target.setField("name");
        target.setProtocol("rsf");
        target.setUnitName("east");
        target.setCursor("cursor:next");
        target.setLimit(25);
        byte[] requestJson = JsonUtils.writeValueAsBytes(new ServicesRequest(target));
        ServicesRequest restored = JsonUtils.readValue(requestJson, new TypeReference<>() {
        });
        JsonNode expectedQuery = JsonUtils.readTree("""
                {"field":"name","protocol":"rsf","search":"order",
                 "unitName":"east","cursor":"cursor:next","limit":25}
                """);
        assertEquals(expectedQuery, JsonUtils.valueToTree(restored.getTarget().directoryQuery()));

        // 分页结果包含真实服务描述及可继续查询的游标，服务信息不会退化成临时 Map。
        JsonNode expectedResult = JsonUtils.readTree("""
                {"items":[{"info":{"serviceId":"orders:OrderService:1","group":"orders",
                  "name":"OrderService","version":"1","role":"Provider","protocols":["rsf","jsonrpc"],
                  "providerCount":2,"consumerCount":1},"enabled":true}],"nextCursor":"cursor:page-2"}
                """);
        ServicesResult result = JsonUtils.treeToValue(expectedResult, ServicesResult.class);
        byte[] resultJson = JsonUtils.writeValueAsBytes(result);
        ServicesResult restoredResult = JsonUtils.readValue(resultJson, new TypeReference<>() {
        });
        assertInstanceOf(MetServiceInfo.class, restoredResult.items().get(0).info());
        assertEquals(expectedResult, JsonUtils.readTree(resultJson));
        assertEquals(expectedResult, JsonUtils.valueToTree(restoredResult));
    }
}
