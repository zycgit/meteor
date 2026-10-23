/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.domain.meta;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.annotation.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 公共声明导出为可存储的数据图，不依赖协议或业务对象实例。 */
public class ServiceDescriptionTest {
    @Test
    public void parameterPositionIsIndependentOfContractFieldNumber() {
        // 参数顺序固定为 Java 调用顺序，注解编号故意采用非连续值。
        JsonNode method = ServiceMetaParser.parse(Named.class).createServiceDescription().path("methods").get(0);
        JsonNode parameters = method.path("parameters");

        // 导出名称、Java 下标和契约编号，三个字段各自保留含义。
        assertEquals("Send", method.path("name").asText());
        assertEquals(0, parameters.get(0).path("argIndex").asInt());
        assertEquals("message", parameters.get(0).path("name").asText());
        assertEquals(10, parameters.get(0).path("fieldId").asInt());
        assertEquals(1, parameters.get(1).path("argIndex").asInt());
        assertEquals(20, parameters.get(1).path("fieldId").asInt());
        assertFalse(parameters.get(0).has("position"));
        assertFalse(parameters.get(0).has("id"));
    }

    @Test
    public void unspecifiedFieldNumberRemainsNull() {
        // 只指定名称不意味着分配了契约字段编号。
        JsonNode method = ServiceMetaParser.parse(Unnumbered.class).createServiceDescription().path("methods").get(0);
        assertTrue(method.path("parameters").get(0).path("fieldId").isNull());
    }

    @Test
    public void serviceContractNumberRetainsTheUnsignedHighHalf() {
        // 公共 JSON 以 long 导出服务编号，不截断 uint32 高半区。
        ObjectNode description = ServiceMetaParser.parse(Named.class).createServiceDescription();
        assertEquals(0xffff_ffffL, description.path("contractId").longValue());
    }

    @Test
    public void methodContractNumberRetainsTheUnsignedHighHalf() {
        // 方法编号与参数字段编号分别保存，方法名称和 Java 签名保持原值。
        ObjectNode description = ServiceMetaParser.parse(Named.class).createServiceDescription();
        JsonNode method = description.path("methods").get(0);
        assertEquals(0xffff_fffeL, method.path("contractId").longValue());
        assertEquals("Send", method.path("name").asText());
        assertEquals("send(java.lang.String,int)", method.path("javaSignature").asText());
        assertEquals(10, method.path("parameters").get(0).path("fieldId").asInt());
    }

    @Test
    public void unspecifiedServiceContractNumberRemainsNull() {
        // 无服务编号的声明显式导出 null，不能以零占位。
        ObjectNode description = ServiceMetaParser.parse(Unnumbered.class).createServiceDescription();
        assertTrue(description.has("contractId"));
        assertTrue(description.path("contractId").isNull());
    }

    @Test
    public void unspecifiedMethodContractNumberRemainsNull() {
        // 没有方法编号时仍导出字段，供管理查询区分缺省与明确编号。
        ObjectNode description = ServiceMetaParser.parse(Unnumbered.class).createServiceDescription();
        JsonNode method = description.path("methods").get(0);
        assertTrue(method.has("contractId"));
        assertTrue(method.path("contractId").isNull());
    }

    @Test
    public void explicitZeroServiceNumberIsExportedAsANumber() {
        // 服务编号零为有效契约编号，不按未声明状态导出为 null。
        ObjectNode description = ServiceMetaParser.parse(ZeroNumbered.class).createServiceDescription();
        assertTrue(description.path("contractId").isIntegralNumber());
        assertEquals(0L, description.path("contractId").longValue());
    }

    @Test
    public void inheritedReturnTypeUsesConcreteGenericArgument() {
        // 继承接口中的 T 在当前服务声明中解析为实际 DTO。
        ObjectNode description = ServiceMetaParser.parse(Concrete.class).createServiceDescription();
        JsonNode method = description.path("methods").get(0);
        String returnType = method.path("returns").path("type").asText();

        // 返回名称和集合元素均来自公共描述，不丢失泛型实参。
        assertEquals("result", method.path("returns").path("name").asText());
        JsonNode collection = description.path("types").path(returnType);
        assertEquals(Dto.class.getName(), collection.path("arguments").get(0).asText());
    }

    @Test
    public void ignoredMethodsDoNotEnterPublishedDescription() {
        // 忽略是公共发布声明的一部分，不由注册中心再次解析注解。
        JsonNode methods = ServiceMetaParser.parse(WithIgnored.class).createServiceDescription().path("methods");
        assertEquals(1, methods.size());
        assertEquals("visible", methods.get(0).path("name").asText());
    }

    @Test
    public void recursiveDtoUsesTypeReference() {
        // 递归 DTO 只登记一个类型，属性通过引用连接回原类型。
        ObjectNode description = ServiceMetaParser.parse(Concrete.class).createServiceDescription();
        JsonNode type = description.path("types").path(Dto.class.getName());
        JsonNode next = type.path("properties").get(0);
        assertEquals("following", next.path("name").asText());
        assertEquals(7, next.path("fieldId").asInt());
        assertEquals(Dto.class.getName(), next.path("type").asText());
    }

    @Test
    public void enumZeroIsARealContractValue() {
        // 枚举显式数值零与未标注常量的空编号分别保留。
        ObjectNode description = ServiceMetaParser.parse(EnumService.class).createServiceDescription();
        JsonNode values = description.path("types").path(State.class.getName()).path("values");
        assertEquals("ready", values.get(0).path("name").asText());
        assertEquals(0, values.get(0).path("fieldId").asInt());
        assertFalse(values.get(0).path("fieldId").isNull());
        assertTrue(values.get(1).path("fieldId").isNull());
    }

    @Test
    public void overloadedMethodsRetainDifferentJavaSignatures() {
        // 同名方法通过完整 Java 参数签名对应各自的端点映射。
        JsonNode methods = ServiceMetaParser.parse(Overloaded.class).createServiceDescription().path("methods");
        assertEquals(2, methods.size());
        assertNotEquals(methods.get(0).path("javaSignature"), methods.get(1).path("javaSignature"));
    }

    @Test
    public void nestedGenericContainersKeepAllTypeReferences() {
        // Map 的值是 List<Dto>，每一级泛型实参分别引用完整类型描述。
        ObjectNode description = ServiceMetaParser.parse(Containers.class).createServiceDescription();
        String result = description.path("methods").get(0).path("returns").path("type").asText();
        JsonNode map = description.path("types").path(result);
        String collection = map.path("arguments").get(1).asText();

        // 不把容器里的 DTO 退化成 Object 或丢失递归类型引用。
        assertEquals("map", map.path("kind").asText());
        JsonNode list = description.path("types").path(collection);
        assertEquals("collection", list.path("kind").asText());
        assertEquals(Dto.class.getName(), list.path("arguments").get(0).asText());
    }

    @Test
    public void publisherReturnKeepsItsElementDescription() {
        // 公共签名保留 Publisher 本身，同时描述流消息的元素类型。
        ObjectNode description = ServiceMetaParser.parse(Streaming.class).createServiceDescription();
        String result = description.path("methods").get(0).path("returns").path("type").asText();
        JsonNode publisher = description.path("types").path(result);
        assertEquals("publisher", publisher.path("kind").asText());
        assertEquals(Dto.class.getName(), publisher.path("arguments").get(0).asText());
    }

    @MetService(id = 0xffff_ffffL)
    public interface Named {
        @MetMethod(id = 0xffff_fffeL, name = "Send")
        void send(@MetParam(name = "message", id = 10) String text, @MetParam(name = "count", id = 20) int times);
    }

    @MetService(id = 0)
    public interface ZeroNumbered extends Named {
    }

    public interface Unnumbered {
        void send(@MetParam(name = "message") String text);
    }

    public interface Generic<T> {
        @MetReturn(name = "result")
        List<T> list();
    }

    public interface Concrete extends Generic<Dto> {
    }

    public static class Dto {
        @MetField(name = "following", id = 7)
        public Dto next;
    }

    public interface WithIgnored {
        String visible();

        @MetIgnore
        String hidden();
    }

    public interface EnumService {
        State state();
    }

    public enum State {
        @MetEnumValue(name = "ready", id = 0) READY,
        UNKNOWN
    }

    public interface Overloaded {
        String echo(String value);

        int echo(int value);
    }

    public interface Containers {
        Map<String, List<Dto>> values();
    }

    public interface Streaming {
        Flow.Publisher<Dto> values();
    }
}
