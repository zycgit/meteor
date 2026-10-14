/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.serialize;
import net.hasor.meteor.serialize.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.meteor.unit.serialize.support.Payload;
import net.hasor.meteor.serialize.coder.JsonSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonSerializeCoderTest {
    private final SerializeCoder coder = new JsonSerializeCoder();

    @Test
    public void unknownFieldsDoNotBreakDtoEvolution() throws Exception {
        // 新版本字段对旧 DTO 保持兼容。
        String json = "{\"name\":\"中文🙂\",\"futureField\":{\"value\":1}}";
        Payload decoded = (Payload) this.coder.decode(json.getBytes(StandardCharsets.UTF_8), Payload.class);
        assertEquals("中文🙂", decoded.getName());
    }

    @Test
    public void missingFieldsRetainJavaDefaults() throws Exception {
        // 缺失字段沿用 DTO 的 Java 默认值。
        byte[] json = "{\"name\":\"only-name\"}".getBytes(StandardCharsets.UTF_8);
        Payload decoded = (Payload) this.coder.decode(json, Payload.class);
        assertEquals(0, decoded.getCount());
    }

    @Test
    public void stringsAreEncodedAsUtf8() throws Exception {
        // JSON 字符串使用 UTF-8 并保留引号。
        String encoded = new String(this.coder.encode("中文"), StandardCharsets.UTF_8);
        assertEquals("\"中文\"", encoded);
    }

    @Test
    public void nullMapEntriesAreOmitted() throws Exception {
        // 映射中的空值项不写入 JSON。
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("present", "value");
        values.put("missing", null);
        String json = new String(this.coder.encode(values), StandardCharsets.UTF_8);
        assertEquals("{\"present\":\"value\"}", json);
    }

    @Test
    public void nullBeanPropertiesAreOmitted() throws Exception {
        // Bean 的空引用字段省略，primitive 默认值仍写入。
        byte[] encoded = this.coder.encode(new Payload());
        Map<?, ?> bean = (Map<?, ?>) this.coder.decode(encoded, Map.class);
        assertFalse(bean.containsKey("name"));
        assertEquals(0, bean.get("count"));
    }

    @Test
    public void emptyBeanEncodesAsEmptyObject() throws Exception {
        // 没有可序列化属性的对象允许写成空 JSON 对象。
        String json = new String(this.coder.encode(new Object()), StandardCharsets.UTF_8);
        assertEquals("{}", json);
    }

    @Test
    public void genericListKeepsElementTypes() throws Exception {
        // 按 Bean 声明的泛型恢复列表元素类型，保留其中的空元素。
        Payload child = new Payload();
        child.setName("child");
        Envelope source = new Envelope();
        source.items = Arrays.asList(child, null);

        byte[] encoded = this.coder.encode(source);
        Envelope restored = (Envelope) this.coder.decode(encoded, Envelope.class);
        assertEquals("child", restored.items.get(0).getName());
        assertNull(restored.items.get(1));
    }

    @Test
    public void nestedGenericMapKeepsElementTypes() throws Exception {
        // 多层 Map/List 泛型恢复为业务 DTO。
        Payload child = new Payload();
        child.setCount(3);
        Envelope source = new Envelope();
        source.nested = Collections.singletonMap("children", Collections.singletonList(child));

        byte[] encoded = this.coder.encode(source);
        Envelope restored = (Envelope) this.coder.decode(encoded, Envelope.class);
        assertEquals(3, restored.nested.get("children").get(0).getCount());
    }

    @Test
    public void dateUsesEpochMilliseconds() throws Exception {
        // 日期以毫秒数表示，解码后恢复原 Date 值。
        Envelope source = new Envelope();
        source.created = new Date(123456789L);
        byte[] encoded = this.coder.encode(source);
        Envelope restored = (Envelope) this.coder.decode(encoded, Envelope.class);
        assertEquals(source.created, restored.created);
        String json = new String(encoded, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"created\":123456789"));
    }

    @Test
    public void typeMetadataCannotOverrideExplicitDto() throws Exception {
        // 报文中的类型提示不能覆盖调用方指定的 DTO 类型。
        String document = "{\"@type\":\"not.a.real.Class\",\"name\":\"safe\",\"count\":2}";
        byte[] json = document.getBytes(StandardCharsets.UTF_8);
        Payload value = (Payload) this.coder.decode(json, Payload.class);
        assertEquals("safe", value.getName());
    }

    @Test
    public void typeMetadataRemainsOrdinaryMapEntry() throws Exception {
        // 无目标 DTO 时，类型提示也只作为普通 JSON 字段。
        String document = "{\"@type\":\"not.a.real.Class\",\"name\":\"safe\"}";
        Object generic = this.coder.decode(document.getBytes(StandardCharsets.UTF_8), Object.class);
        assertTrue(generic instanceof Map);
        assertEquals("not.a.real.Class", ((Map<?, ?>) generic).get("@type"));
    }

    @Test
    public void emptyDocumentFailsWithoutPoisoningCoder() throws Exception {
        // 当前非法文档失败后，后续独立请求仍能成功。
        byte[] document = "".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> this.coder.decode(document, Object.class));
        byte[] next = this.coder.encode("next");
        assertEquals("next", this.coder.decode(next, String.class));
    }

    @Test
    public void blankDocumentFailsWithoutPoisoningCoder() throws Exception {
        // 当前非法文档失败后，后续独立请求仍能成功。
        byte[] document = " ".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> this.coder.decode(document, Object.class));
        byte[] next = this.coder.encode("next");
        assertEquals("next", this.coder.decode(next, String.class));
    }

    @Test
    public void truncatedObjectFailsWithoutPoisoningCoder() throws Exception {
        // 当前非法文档失败后，后续独立请求仍能成功。
        byte[] document = "{".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> this.coder.decode(document, Object.class));
        byte[] next = this.coder.encode("next");
        assertEquals("next", this.coder.decode(next, String.class));
    }

    @Test
    public void missingPropertyValueFailsWithoutPoisoningCoder() throws Exception {
        // 当前非法文档失败后，后续独立请求仍能成功。
        byte[] document = "{\"name\":}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> this.coder.decode(document, Object.class));
        byte[] next = this.coder.encode("next");
        assertEquals("next", this.coder.decode(next, String.class));
    }

    @Test
    public void multipleObjectsFailsWithoutPoisoningCoder() throws Exception {
        // 当前非法文档失败后，后续独立请求仍能成功。
        byte[] document = "{} {}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> this.coder.decode(document, Object.class));
        byte[] next = this.coder.encode("next");
        assertEquals("next", this.coder.decode(next, String.class));
    }

    @Test
    public void nullFollowedByObjectFailsWithoutPoisoningCoder() throws Exception {
        // 当前非法文档失败后，后续独立请求仍能成功。
        byte[] document = "null {}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> this.coder.decode(document, Object.class));
        byte[] next = this.coder.encode("next");
        assertEquals("next", this.coder.decode(next, String.class));
    }

    @Test
    public void numberFollowedByBooleanFailsWithoutPoisoningCoder() throws Exception {
        // 当前非法文档失败后，后续独立请求仍能成功。
        byte[] document = "42 true".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> this.coder.decode(document, Object.class));
        byte[] next = this.coder.encode("next");
        assertEquals("next", this.coder.decode(next, String.class));
    }

    @Test
    public void malformedUtf8Fails() throws Exception {
        // 字符串里的 UTF-8 序列不完整时向调用者报告 I/O 错误。
        byte[] malformed = { '"', (byte) 0xc3, '"' };
        assertThrows(IOException.class, () -> this.coder.decode(malformed, String.class));
    }

    @Test
    public void beanGetterFailuresUseTheCoderIOExceptionContract() throws Exception {
        // getter 的业务异常按编码器 I/O 契约抛出，并保留原因。
        IOException failure = assertThrows(IOException.class, () -> this.coder.encode(new BrokenBean()));
        assertNotNull(failure.getCause());

        // 本次失败不污染同一个编码器的后续调用。
        byte[] next = this.coder.encode("next");
        assertEquals("next", this.coder.decode(next, String.class));
    }

    public static class Envelope {
        public List<Payload>              items;
        public Map<String, List<Payload>> nested;
        public Date                       created;
    }

    public static class BrokenBean {
        public String getValue() {
            throw new IllegalStateException("broken getter");
        }
    }
}
