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

    public interface Named {
        @MetMethod(name = "Send")
        void send(@MetParam(name = "message", id = 10) String text, @MetParam(name = "count", id = 20) int times);
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
