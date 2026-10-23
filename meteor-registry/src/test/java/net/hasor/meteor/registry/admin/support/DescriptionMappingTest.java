/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.registry.admin.description.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.Test;
import static org.junit.Assert.*;

/** 注册描述的公开 DTO 映射；内部输入使用 JSON，返回值只保留 Java 纯数据。 */
public class DescriptionMappingTest {
    @Test
    public void mapsTheJavaDeclarationWithoutChangingGenericReferencesOrOrder() throws Exception {
        // 参数、类型参数和枚举值分别按声明顺序保存，递归属性只保存完整类型引用。
        ObjectNode source = (ObjectNode) new JsonMapper().readTree("""
                {
                  "javaType": "example.LookupService",
                  "contractServiceId": 4294967295,
                  "message": false,
                  "methods": [{
                    "javaSignature": "lookup(example.Query,int)",
                    "javaName": "lookup",
                    "name": "Lookup",
                    "methodId": 4294967294,
                    "message": true,
                    "parameters": [
                      {"argIndex": 0, "name": "query", "type": "example.Query", "fieldId": 9},
                      {"argIndex": 1, "name": "limit", "type": "int", "fieldId": 2}
                    ],
                    "returns": {"name": "result", "type": "example.Box<java.lang.String,example.Query>"},
                    "exceptions": ["example.LookupException", "java.io.IOException"]
                  }],
                  "types": {
                    "example.Box<java.lang.String,example.Query>": {
                      "javaType": "example.Box",
                      "name": "Box",
                      "kind": "object",
                      "arguments": ["java.lang.String", "example.Query"],
                      "properties": [
                        {"javaName": "next", "name": "Next", "fieldId": 7,
                         "type": "example.Box<java.lang.String,example.Query>"}
                      ],
                      "values": [
                        {"javaName": "SECOND", "name": "second", "fieldId": 2},
                        {"javaName": "FIRST", "name": "first", "fieldId": 1}
                      ]
                    },
                    "example.Query[]": {
                      "javaType": "example.Query[]", "name": "Query[]", "kind": "array",
                      "elementType": "example.Query"
                    }
                  },
                  "protocols": {
                    "rsf": {"tcp-main": {"name": "lookup", "serializeType": "Hessian2"}}
                  }
                }
                """);

        // 普通 Java 描述使用固定字段，不能把泛型引用合并成同一个原始类型。
        MetServiceDescription description = DescriptionMapping.service(source);
        assertEquals("example.LookupService", description.getJavaType());
        assertEquals(Long.valueOf(0xffff_ffffL), description.getContractServiceId());
        assertFalse(description.isMessage());
        assertEquals(1, description.getMethods().size());
        MetMethodDescription method = description.getMethods().get(0);
        assertEquals("lookup(example.Query,int)", method.getJavaSignature());
        assertEquals("lookup", method.getJavaName());
        assertEquals("Lookup", method.getName());
        assertEquals(Long.valueOf(0xffff_fffeL), method.getMethodId());
        assertTrue(method.isMessage());
        assertEquals(2, method.getParameters().size());
        MetFieldDescription first = method.getParameters().get(0);
        assertEquals(Integer.valueOf(0), first.getArgIndex());
        assertEquals("query", first.getName());
        assertEquals("example.Query", first.getType());
        assertEquals(Long.valueOf(9), first.getFieldId());
        MetFieldDescription second = method.getParameters().get(1);
        assertEquals(Integer.valueOf(1), second.getArgIndex());
        assertEquals("limit", second.getName());
        assertEquals(Long.valueOf(2), second.getFieldId());
        assertEquals("result", method.getReturns().getName());
        String boxReference = "example.Box<java.lang.String,example.Query>";
        assertEquals(boxReference, method.getReturns().getType());
        assertEquals("example.LookupException", method.getExceptions().get(0));
        assertEquals("java.io.IOException", method.getExceptions().get(1));

        // 类型图保留插入顺序和引用关系，不递归实例化 Java 类型或展开循环对象。
        String[] typeReferences = description.getTypes().keySet().toArray(String[]::new);
        assertArrayEquals(new String[] { boxReference, "example.Query[]" }, typeReferences);
        MetTypeDescription box = description.getTypes().get(boxReference);
        assertEquals("example.Box", box.getJavaType());
        assertEquals("Box", box.getName());
        assertEquals("object", box.getKind());
        assertEquals("java.lang.String", box.getArguments().get(0));
        assertEquals("example.Query", box.getArguments().get(1));
        MetFieldDescription property = box.getProperties().get(0);
        assertEquals("next", property.getJavaName());
        assertEquals("Next", property.getName());
        assertEquals(boxReference, property.getType());
        assertEquals(Long.valueOf(7), property.getFieldId());
        assertEquals("SECOND", box.getValues().get(0).getJavaName());
        assertEquals("second", box.getValues().get(0).getName());
        assertEquals(Long.valueOf(2), box.getValues().get(0).getFieldId());
        assertEquals("FIRST", box.getValues().get(1).getJavaName());
        MetTypeDescription array = description.getTypes().get("example.Query[]");
        assertEquals("array", array.getKind());
        assertEquals("example.Query", array.getElementType());
        MetProtocolDescription protocol = description.getProtocols().get("rsf").get("tcp-main");
        assertEquals("lookup", protocol.getName());
        assertEquals("Hessian2", protocol.getSerializeType());
    }

    @Test
    public void retainsUnknownFieldsAndTheLiteralExtensionsNameAtEveryLevel() throws Exception {
        // 新版本描述可能在任意层加入字段，原本叫 extensions 的字段也只是一个未知属性。
        ObjectNode source = (ObjectNode) new JsonMapper().readTree("""
                {
                  "extensions": {"marker": "service", "nested": [{"present": null}, ["tail"]]},
                  "futureService": null,
                  "methods": [{
                    "name": "lookup", "extensions": {"marker": "method"},
                    "futureMethod": [true, 3.25],
                    "parameters": [{"name": "query", "extensions": {"marker": "field"},
                                    "futureField": {"items": [null, "value"]}}]
                  }],
                  "types": {"example.Query": {
                    "kind": "future-kind", "extensions": {"marker": "type"}, "futureType": false
                  }},
                  "protocols": {"custom": {"first": {
                    "extensions": {"marker": "protocol"}, "futureProtocol": [{"value": null}]
                  }}}
                }
                """);

        // 未知属性进入所在 DTO 的扩展表，嵌套对象和数组必须全部变为 JDK 数据。
        MetServiceDescription description = DescriptionMapping.service(source);
        Map<String, Object> service = description.getExtensions();
        assertTrue(service.containsKey("futureService"));
        assertNull(service.get("futureService"));
        Map<String, Object> originalExtensions = object(service.get("extensions"));
        assertEquals("service", originalExtensions.get("marker"));
        List<Object> nested = array(originalExtensions.get("nested"));
        Map<String, Object> present = object(nested.get(0));
        assertTrue(present.containsKey("present"));
        assertNull(present.get("present"));
        assertEquals("tail", array(nested.get(1)).get(0));
        assertJdkTree(service);

        MetMethodDescription method = description.getMethods().get(0);
        assertEquals("method", object(method.getExtensions().get("extensions")).get("marker"));
        assertEquals(Boolean.TRUE, array(method.getExtensions().get("futureMethod")).get(0));
        assertJdkTree(method.getExtensions());
        MetFieldDescription parameter = method.getParameters().get(0);
        assertEquals("field", object(parameter.getExtensions().get("extensions")).get("marker"));
        assertJdkTree(parameter.getExtensions());
        MetTypeDescription type = description.getTypes().get("example.Query");
        assertEquals("future-kind", type.getKind());
        assertEquals("type", object(type.getExtensions().get("extensions")).get("marker"));
        assertEquals(Boolean.FALSE, type.getExtensions().get("futureType"));
        assertJdkTree(type.getExtensions());
        MetProtocolDescription protocol = description.getProtocols().get("custom").get("first");
        assertEquals("protocol", object(protocol.getExtensions().get("extensions")).get("marker"));
        assertJdkTree(protocol.getExtensions());
    }

    @Test
    public void keepsAbsentZeroAndNegativeContractNumbersDistinct() throws Exception {
        // 未声明编号、显式零和带符号字段号都是原始描述数据，映射不能替换为同一个默认值。
        ObjectNode source = (ObjectNode) new JsonMapper().readTree("""
                {"contractServiceId": null, "methods": [
                  {"name": "absent", "methodId": null, "parameters": [{"fieldId": null}]},
                  {"name": "zero", "methodId": 0, "parameters": [{"argIndex": 0, "fieldId": 0}]},
                  {"name": "signed", "methodId": -2, "parameters": [{"argIndex": 1, "fieldId": -3}]}
                ]}
                """);

        // 包装类型保留 null；数值读取不采用会截断或吞掉符号的转换。
        MetServiceDescription description = DescriptionMapping.service(source);
        assertNull(description.getContractServiceId());
        MetMethodDescription absent = description.getMethods().get(0);
        assertNull(absent.getMethodId());
        assertNull(absent.getParameters().get(0).getArgIndex());
        assertNull(absent.getParameters().get(0).getFieldId());
        MetMethodDescription zero = description.getMethods().get(1);
        assertEquals(Long.valueOf(0), zero.getMethodId());
        assertEquals(Integer.valueOf(0), zero.getParameters().get(0).getArgIndex());
        assertEquals(Long.valueOf(0), zero.getParameters().get(0).getFieldId());
        MetMethodDescription signed = description.getMethods().get(2);
        assertEquals(Long.valueOf(-2), signed.getMethodId());
        assertEquals(Long.valueOf(-3), signed.getParameters().get(0).getFieldId());
    }

    @Test
    public void returnsDetachedTypedAndDynamicData() throws Exception {
        // 输入模拟注册中心已经保存的正文，公共字段与协议扩展都包含多层可变结构。
        ObjectNode source = (ObjectNode) new JsonMapper().readTree("""
                {
                  "javaType": "example.Service",
                  "future": {"nested": [{"value": "original"}]},
                  "methods": [{"name": "lookup", "parameters": [{"name": "query"}]}],
                  "types": {"example.Query": {"arguments": ["java.lang.String"],
                    "properties": [{"name": "text"}]}},
                  "protocols": {"custom": {"first": {
                    "methods": [{"name": "wireLookup", "returns": [{"type": "wire.Result"}]}],
                    "types": {"wire.Result": {"fields": [{"name": "value"}]}},
                    "future": {"nested": ["original"]}
                  }}}
                }
                """);
        String stored = source.toString();
        MetServiceDescription returned = DescriptionMapping.service(source);

        // 修改 DTO、扩展树及协议方法和类型图，不得把对象所有权反向带回内部 JSON。
        returned.setJavaType("changed");
        returned.getMethods().get(0).getParameters().get(0).setName("changed");
        MetTypeDescription type = returned.getTypes().get("example.Query");
        type.getArguments().set(0, "changed");
        type.getProperties().get(0).setName("changed");
        List<Object> nested = array(object(returned.getExtensions().get("future")).get("nested"));
        object(nested.get(0)).put("value", "changed");
        MetProtocolDescription protocol = returned.getProtocols().get("custom").get("first");
        protocol.getMethods().get(0).put("name", "changed");
        object(array(protocol.getMethods().get(0).get("returns")).get(0)).put("type", "changed");
        Map<String, Object> wireType = object(protocol.getTypes().get("wire.Result"));
        object(array(wireType.get("fields")).get(0)).put("name", "changed");
        array(object(protocol.getExtensions().get("future")).get("nested")).set(0, "changed");
        assertEquals(stored, source.toString());

        // 再次读取仍来自原始正文，各层不是上次查询返回值的共享容器。
        MetServiceDescription fresh = DescriptionMapping.service(source);
        assertEquals("example.Service", fresh.getJavaType());
        assertEquals("query", fresh.getMethods().get(0).getParameters().get(0).getName());
        assertEquals("java.lang.String", fresh.getTypes().get("example.Query").getArguments().get(0));
        assertEquals("text", fresh.getTypes().get("example.Query").getProperties().get(0).getName());
        List<Object> freshNested = array(object(fresh.getExtensions().get("future")).get("nested"));
        assertEquals("original", object(freshNested.get(0)).get("value"));
        MetProtocolDescription freshProtocol = fresh.getProtocols().get("custom").get("first");
        assertEquals("wireLookup", freshProtocol.getMethods().get(0).get("name"));
        Object freshReturn = array(freshProtocol.getMethods().get(0).get("returns")).get(0);
        assertEquals("wire.Result", object(freshReturn).get("type"));
        Map<String, Object> freshType = object(freshProtocol.getTypes().get("wire.Result"));
        assertEquals("value", object(array(freshType.get("fields")).get(0)).get("name"));
        List<Object> future = array(object(freshProtocol.getExtensions().get("future")).get("nested"));
        assertEquals("original", future.get(0));
    }

    @Test
    public void retainsSoapMultipartReturnsAsDynamicProtocolData() throws Exception {
        // SOAP 的返回值是多 part 数组，并非公共 Java 方法的单个返回字段。
        ObjectNode source = (ObjectNode) new JsonMapper().readTree("""
                {"protocols": {"soap": {"http-main": {
                  "name": "Lookup", "serializeType": "Xml", "version": "1.2",
                  "namespace": "urn:lookup", "contextExact": true,
                  "methods": [{"name": "lookup", "returns": [
                    {"name": "result", "type": "{urn:lookup}Result"},
                    {"name": "status", "type": "{urn:lookup}Status"}
                  ], "faults": {"LookupFault": {"minOccurs": 0, "maxOccurs": "unbounded"}}}],
                  "types": {"example.Result": {"namespace": "urn:lookup", "properties": []}},
                  "schemas": [{"namespace": "urn:lookup", "content": "schema-text"}]
                }}}}
                """);

        // 协议专属结构原样转为 JDK 树，不借用公共返回 DTO 或猜测 QName 类型。
        MetProtocolDescription soap = DescriptionMapping.service(source).getProtocols().get("soap").get("http-main");
        assertEquals("Lookup", soap.getName());
        assertEquals("Xml", soap.getSerializeType());
        assertEquals("1.2", soap.getExtensions().get("version"));
        assertEquals("urn:lookup", soap.getExtensions().get("namespace"));
        List<Object> returns = array(soap.getMethods().get(0).get("returns"));
        assertEquals(2, returns.size());
        assertEquals("result", object(returns.get(0)).get("name"));
        assertEquals("{urn:lookup}Status", object(returns.get(1)).get("type"));
        Map<String, Object> faults = object(soap.getMethods().get(0).get("faults"));
        assertEquals("unbounded", object(faults.get("LookupFault")).get("maxOccurs"));
        assertEquals("urn:lookup", object(soap.getTypes().get("example.Result")).get("namespace"));
        assertEquals("schema-text", object(array(soap.getExtensions().get("schemas")).get(0)).get("content"));
        assertJdkTree(soap.getMethods());
        assertJdkTree(soap.getTypes());
        assertJdkTree(soap.getExtensions());
    }

    @Test
    public void retainsNumericOncRpcProgramAndVersion() throws Exception {
        // ONC RPC 的程序号和版本号是数值，不沿用 SOAP 的字符串版本语义。
        ObjectNode source = (ObjectNode) new JsonMapper().readTree("""
                {"protocols": {"oncrpc": {"tcp-main": {
                  "name": "Lookup", "program": 4294967295, "version": 4294967294,
                  "methods": [{"name": "lookup", "procedureId": 17,
                    "requestType": "lookup.input", "responseType": "lookup.result", "argIndexes": [1, 0]}],
                  "types": {"lookup.input": {"kind": "union", "discriminator": {"type": "int"},
                    "members": [{"name": "value", "cases": [0, 4294967295], "defaultArm": false}]}}
                }}}}
                """);

        // 大整数仍然是 Number，过程参数位置和 union 分支数组都不重排。
        MetProtocolDescription onc = DescriptionMapping.service(source).getProtocols().get("oncrpc").get("tcp-main");
        Object program = onc.getExtensions().get("program");
        Object version = onc.getExtensions().get("version");
        assertTrue(program instanceof Number);
        assertTrue(version instanceof Number);
        assertEquals(0xffff_ffffL, ((Number) program).longValue());
        assertEquals(0xffff_fffeL, ((Number) version).longValue());
        Map<String, Object> method = onc.getMethods().get(0);
        assertEquals(17, ((Number) method.get("procedureId")).intValue());
        assertEquals(1, ((Number) array(method.get("argIndexes")).get(0)).intValue());
        assertEquals(0, ((Number) array(method.get("argIndexes")).get(1)).intValue());
        Map<String, Object> input = object(onc.getTypes().get("lookup.input"));
        assertEquals("int", object(input.get("discriminator")).get("type"));
        Map<String, Object> member = object(array(input.get("members")).get(0));
        assertEquals(0xffff_ffffL, ((Number) array(member.get("cases")).get(1)).longValue());
        assertJdkTree(onc.getMethods());
        assertJdkTree(onc.getTypes());
    }

    @Test
    public void keepsHighPrecisionNumbersInExtensionsAndDynamicProtocolData() {
        // 扩展允许超过 long 范围的整数和高精度小数，不能经通用 Object 映射降为 double。
        BigDecimal amount = new BigDecimal("12345678901234567890.12345678901234567890123456789");
        BigInteger serial = new BigInteger("18446744073709551617");
        ObjectNode source = new JsonMapper().createObjectNode();
        source.putObject("future").put("amount", amount).put("serial", serial);
        ObjectNode contract = source.putObject("protocols").putObject("custom").putObject("main");
        contract.putArray("methods").addObject().put("amount", amount).put("serial", serial);
        contract.putObject("types").putObject("Value").put("amount", amount).put("serial", serial);

        // 根扩展、协议方法和类型图都保留 Number 本身的精度，而不是只保留近似显示值。
        MetServiceDescription description = DescriptionMapping.service(source);
        Map<String, Object> future = object(description.getExtensions().get("future"));
        assertEquals(amount, future.get("amount"));
        assertEquals(serial, future.get("serial"));
        MetProtocolDescription protocol = description.getProtocols().get("custom").get("main");
        assertEquals(amount, protocol.getMethods().get(0).get("amount"));
        assertEquals(serial, protocol.getMethods().get(0).get("serial"));
        Map<String, Object> type = object(protocol.getTypes().get("Value"));
        assertEquals(amount, type.get("amount"));
        assertEquals(serial, type.get("serial"));
    }

    @Test
    public void createsIndependentEmptyCollectionsForAnOlderMinimalDescription() throws Exception {
        // 兼容只上报少量根属性的历史正文，不要求旧实例补齐所有集合字段。
        ObjectNode source = (ObjectNode) new JsonMapper().readTree("{\"javaType\":\"example.Legacy\"}");
        MetServiceDescription first = DescriptionMapping.service(source);
        assertEquals("example.Legacy", first.getJavaType());
        assertNull(first.getContractServiceId());
        assertTrue(first.getMethods().isEmpty());
        assertTrue(first.getTypes().isEmpty());
        assertTrue(first.getProtocols().isEmpty());
        assertTrue(first.getExtensions().isEmpty());

        // 返回集合可以供调用方修改，但不能成为下一次查询的默认共享实例。
        first.getMethods().add(new MetMethodDescription());
        first.getTypes().put("changed", new MetTypeDescription());
        first.getExtensions().put("changed", true);
        MetServiceDescription second = DescriptionMapping.service(source);
        assertTrue(second.getMethods().isEmpty());
        assertTrue(second.getTypes().isEmpty());
        assertTrue(second.getExtensions().isEmpty());
        assertFalse(source.has("methods"));
    }

    @Test
    public void serializesFieldIdentityBeforeItsBodyAndExtensions() {
        // 字段描述按公共编号、公共名称、Java 位置和类型、扩展的顺序输出。
        String[] expected = { "fieldId", "name", "argIndex", "javaName", "type", "extensions" };
        assertArrayEquals(expected, serializedFields(new MetFieldDescription()));
    }

    @Test
    public void serializesMethodIdentityBeforeItsBodyAndExtensions() {
        // 方法编号和公共名称先于 Java 绑定信息，调用标记与扩展位于主体之后。
        String[] expected = { "methodId", "name", "javaSignature", "javaName", "parameters", //
                "returns", "exceptions", "message", "extensions" };
        assertArrayEquals(expected, serializedFields(new MetMethodDescription()));
    }

    @Test
    public void serializesServiceIdentityBeforeItsBodyAndExtensions() {
        // 服务编号和接口类型先于方法、类型图及端点，消息标记与扩展随后输出。
        String[] expected = { "contractServiceId", "javaType", "methods", "types", "protocols", //
                "message", "extensions" };
        assertArrayEquals(expected, serializedFields(new MetServiceDescription()));
    }

    @Test
    public void serializesTypeIdentityBeforeItsShapeAndExtensions() {
        // 公共类型名称与 Java 类型先于容器、属性、枚举形状和扩展。
        String[] expected = { "name", "javaType", "kind", "elementType", "arguments", //
                "properties", "values", "extensions" };
        assertArrayEquals(expected, serializedFields(new MetTypeDescription()));
    }

    @Test
    public void serializesProtocolNameBeforeItsBodyAndExtensions() {
        // 协议端点名称统一使用 name，后续仍保留协议自己的方法和类型图形状。
        String[] expected = { "name", "serializeType", "methods", "types", "extensions" };
        assertArrayEquals(expected, serializedFields(new MetProtocolDescription()));
    }

    private static String[] serializedFields(Object description) {
        // 使用正式 JSON 输出观察公开数据，不反射 DTO 的字段声明。
        ObjectNode serialized = RegistryJson.copy(description, ObjectNode.class);
        List<String> fields = new ArrayList<>();
        serialized.fieldNames().forEachRemaining(fields::add);
        return fields.toArray(String[]::new);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        assertTrue("描述中的对象必须是 JDK Map", value instanceof Map<?, ?>);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        assertTrue("描述中的数组必须是 JDK List", value instanceof List<?>);
        return (List<Object>) value;
    }

    private static void assertJdkTree(Object value) {
        assertFalse("公开描述不能残留 Jackson 节点", value instanceof JsonNode);
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                assertTrue(entry.getKey() instanceof String);
                assertJdkTree(entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                assertJdkTree(item);
            }
        } else {
            boolean scalar = value == null || value instanceof String || value instanceof Boolean;
            assertTrue("扩展值只能包含 JDK JSON 数据", scalar || value instanceof Number);
        }
    }
}
