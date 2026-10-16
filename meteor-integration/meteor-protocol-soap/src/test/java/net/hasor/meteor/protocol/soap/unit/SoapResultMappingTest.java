/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.unit;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import net.hasor.meteor.protocol.soap.codec.SoapResultMappingAccess;
import net.hasor.meteor.protocol.soap.unit.support.ResultMappingValues.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoapResultMappingTest {
    private static final Type STRING_LIST_MAP  = new TypeReference<Map<String, List<String>>>() {
    }.getType();
    private static final Type STRING_LIST_PAIR = new TypeReference<Pair<List<String>>>() {
    }.getType();

    @Test
    public void zeroPartsHaveNoBusinessResult() {
        // 空输出消息不要求实例化返回对象或提供 Java 值。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(void.class);

        // 类型、拆分值都为空，合并结果为 null。
        assertEquals(0, mapper.types().length);
        assertEquals(0, mapper.split(null).length);
        assertNull(mapper.join());
    }

    @Test
    public void singleMapPartKeepsTheWholeResultAndItsGenericType() {
        // 只有一个 part 时，整个 Map 就是该 part 的值。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(STRING_LIST_MAP, "payload");
        Map<String, List<String>> value = Map.of("data", List.of("shared"));

        // 不能按 part 名从 Map 取值，也不能丢失单值的泛型声明。
        Object[] parts = mapper.split(value);
        assertEquals(1, parts.length);
        assertSame(value, parts[0]);
        assertSame(STRING_LIST_MAP, mapper.types()[0]);
        assertSame(value, mapper.join(parts));
    }

    @Test
    public void mapSplitUsesPartNamesAndKeepsSharedReferences() {
        // 两个命名 part 指向同一个可变集合，契约顺序与 Map 构造顺序独立。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(STRING_LIST_MAP, "second", "first");
        List<String> shared = new ArrayList<>(List.of("same"));
        Map<String, List<String>> value = Map.of("first", shared, "second", shared);

        // 按 WSDL part 名拆分，不因转换为 XML 中间对象而复制集合。
        Object[] parts = mapper.split(value);
        assertSame(shared, parts[0]);
        assertSame(shared, parts[1]);
    }

    @Test
    public void mapJoinUsesPartNamesAndKeepsSharedReferences() {
        // 解码器为两个输出 part 返回同一个对象引用。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(STRING_LIST_MAP, "second", "first");
        List<String> shared = new ArrayList<>(List.of("same"));

        // 返回 Map 使用 part 名与声明顺序，并保留输入引用。
        Map<?, ?> result = (Map<?, ?>) mapper.join(shared, shared);
        assertEquals(List.of("second", "first"), new ArrayList<>(result.keySet()));
        assertSame(shared, result.get("first"));
        assertSame(shared, result.get("second"));
    }

    @Test
    public void mapPartTypesRetainTheirGenericValueType() {
        // Map 的 value 本身是 List<String>，每个 part 都应按该完整类型解码。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(STRING_LIST_MAP, "first", "second");

        // 泛型元素类型没有被降为 Object 或原始 List。
        JavaType first = (JavaType) mapper.types()[0];
        JavaType second = (JavaType) mapper.types()[1];
        assertEquals(List.class, first.getRawClass());
        assertEquals(String.class, first.getContentType().getRawClass());
        assertEquals(first, second);
    }

    @Test
    public void abstractSortedMapRetainsItsDeclaredContract() {
        // 返回类型声明为 SortedMap，普通 LinkedHashMap 无法满足该接口。
        Type type = new TypeReference<SortedMap<String, List<String>>>() {
        }.getType();
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(type, "second", "first");
        List<String> shared = new ArrayList<>(List.of("same"));

        // 创建正确的 Map 实现仍不复制 part 已解码的对象。
        Object value = mapper.join(shared, shared);
        assertTrue(value instanceof SortedMap<?, ?>);
        SortedMap<?, ?> result = (SortedMap<?, ?>) value;
        assertEquals(List.of("first", "second"), new ArrayList<>(result.keySet()));
        assertSame(shared, result.get("first"));
        assertSame(shared, result.get("second"));
    }

    @Test
    public void dtoSplitKeepsSharedPropertyReferences() {
        // 普通泛型 DTO 的两个属性保存同一个集合。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(STRING_LIST_PAIR, "first", "second");
        List<String> shared = new ArrayList<>(List.of("same"));
        Pair<List<String>> value = new Pair<>();
        value.setFirst(shared);
        value.setSecond(shared);

        // 属性读取直接保留原对象，不经过二次序列化。
        Object[] parts = mapper.split(value);
        assertSame(shared, parts[0]);
        assertSame(shared, parts[1]);
    }

    @Test
    public void dtoJoinKeepsSharedPartReferences() {
        // 两个 part 已由 encoded 解码器恢复为共享对象。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(STRING_LIST_PAIR, "first", "second");
        List<String> shared = new ArrayList<>(List.of("same"));

        // 为 DTO 赋值时保留同一引用，不能重新构造各个属性值。
        Pair<?> result = (Pair<?>) mapper.join(shared, shared);
        assertSame(shared, result.getFirst());
        assertSame(shared, result.getSecond());
    }

    @Test
    public void genericDtoPropertiesResolveTheirBoundElementType() {
        // 返回类型为 Pair<List<String>>，属性类型 T 需要按此绑定解析。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(STRING_LIST_PAIR, "first", "second");

        // 每个 part 获取解析后的 List<String>，而不是类型变量或 Object。
        JavaType first = (JavaType) mapper.types()[0];
        JavaType second = (JavaType) mapper.types()[1];
        assertEquals(List.class, first.getRawClass());
        assertEquals(String.class, first.getContentType().getRawClass());
        assertEquals(first, second);
    }

    @Test
    public void nullMultipartResultProvidesNullPartValues() {
        // 多 part 返回对象为空，值映射保留每个 part 的位置。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(STRING_LIST_PAIR, "first", "second");

        // 后续 Body 编码仍能依据各 part 的 optional/nil 规则处理空值。
        assertArrayEquals(new Object[] { null, null }, mapper.split(null));
    }

    @Test
    public void missingDtoPropertyReportsItsPartName() {
        // 契约要求 DTO 没有声明的 missing 属性。
        Type type = new TypeReference<Pair<String>>() {
        }.getType();

        // 构建映射时直接报告具体 part，不延迟为反射错误。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapResultMappingAccess(type, "missing", "first"));
        assertEquals("SOAP output part has no Java result property: missing", failure.getMessage());
    }

    @Test
    public void getterOnlyDtoCannotReceivePartValues() {
        // DTO 只有 getter，没有可写字段或 setter。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(GetterOnly.class, "first", "second");

        // 合并失败明确指出不能写入的 part。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, () -> mapper.join("a", "b"));
        assertEquals("SOAP output part has no writable property: first", failure.getMessage());
    }

    @Test
    public void getterOnlyDtoCanProvidePartValues() {
        // 无 setter 的 DTO 仍具有完整的只读输出能力。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(GetterOnly.class, "first", "second");

        // 类型发现不应因缺少反方向的写入成员而阻止拆分。
        assertArrayEquals(new Object[] { "first", "second" }, mapper.split(new GetterOnly()));
    }

    @Test
    public void setterOnlyDtoCannotProvidePartValues() {
        // DTO 只有 setter，没有可读字段或 getter。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(SetterOnly.class, "first", "second");
        SetterOnly value = new SetterOnly();

        // 拆分失败明确指出不能读取的 part。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, () -> mapper.split(value));
        assertEquals("SOAP output part has no readable property: first", failure.getMessage());
    }

    @Test
    public void setterOnlyDtoCanReceivePartValues() {
        // 无 getter 的 DTO 仍可通过 setter 接收两个输出 part。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(SetterOnly.class, "first", "second");

        // 各 part 按名称写入，使用公开观察方法检查赋值结果。
        SetterOnly result = (SetterOnly) mapper.join("a", "b");
        assertEquals("a", result.firstValue());
        assertEquals("b", result.secondValue());
    }

    @Test
    public void writeOnlyPropertyCannotBeReadForAnOutputPart() {
        // getter 虽然存在，Jackson WRITE_ONLY 声明仍禁止将其输出。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(WriteOnlyProperty.class, "first", "second");
        WriteOnlyProperty value = new WriteOnlyProperty();
        value.setFirst("private");

        // 不能用反射绕过属性自身的序列化方向。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, () -> mapper.split(value));
        assertEquals("SOAP output part has no readable property: first", failure.getMessage());
    }

    @Test
    public void readOnlyPropertyCannotReceiveAnOutputPart() {
        // setter 虽然存在，Jackson READ_ONLY 声明仍禁止反序列化赋值。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(ReadOnlyProperty.class, "first", "second");

        // 不因成员可反射访问而绕过属性自身的反序列化方向。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, () -> mapper.join("a", "b"));
        assertEquals("SOAP output part has no writable property: first", failure.getMessage());
    }

    @Test
    public void mapKeysMustAcceptStringPartNames() {
        // Integer 键的 Map 不能接收 WSDL 的字符串 part 名。
        Type type = new TypeReference<Map<Integer, String>>() {
        }.getType();

        // 不创建表面类型正确、实际键类型错误的返回 Map。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapResultMappingAccess(type, "first", "second"));
        assertEquals("SOAP output Map keys must accept String part names", failure.getMessage());
    }

    @Test
    public void missingPartValuesCannotBecomeASinglePartResult() {
        // 多 part 契约实际只获得一个解码值。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(STRING_LIST_MAP, "first", "second");

        // 不能按数组长度误走单 part 分支，静默返回第一个值。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, () -> mapper.join(List.of("a")));
        assertEquals("SOAP output part and Java value counts do not match", failure.getMessage());
    }

    @Test
    public void extraPartValuesAreRejectedExplicitly() {
        // 单 part 契约却获得两个解码值。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(String.class, "only");

        // 不以数组越界等内部异常报告契约与值数量错误。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, () -> mapper.join("a", "b"));
        assertEquals("SOAP output part and Java value counts do not match", failure.getMessage());
    }

    @Test
    public void returnedTypeArrayCannotMutateTheMapper() {
        // 调用方获得类型数组用于正文编解码。
        SoapResultMappingAccess mapper = new SoapResultMappingAccess(String.class, "only");
        Type[] types = mapper.types();

        // 修改返回数组不会影响后续调用看到的契约类型。
        types[0] = Integer.class;
        assertSame(String.class, mapper.types()[0]);
    }
}
