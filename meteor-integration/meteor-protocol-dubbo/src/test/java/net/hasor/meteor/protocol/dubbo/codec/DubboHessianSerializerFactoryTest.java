/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.caucho.hessian.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DubboHessianSerializerFactoryTest {
    @Test
    public void selectsContainerSerializerForImmutableCollection() throws Exception {
        // JDK 不可变容器必须避开其内部类的反射序列化器。
        DubboHessianSerializerFactory factory = new DubboHessianSerializerFactory(this.getClass().getClassLoader());
        Serializer serializer = factory.getSerializer(List.of("first").getClass());
        assertTrue(serializer instanceof CollectionSerializer);
    }

    @Test
    public void selectsMapSerializerForImmutableMap() throws Exception {
        // Map 通过键值编码，不访问 JDK 内部的 writeReplace。
        DubboHessianSerializerFactory factory = new DubboHessianSerializerFactory(this.getClass().getClassLoader());
        Serializer serializer = factory.getSerializer(Map.of("first", 1).getClass());
        assertTrue(serializer instanceof MapSerializer);
    }

    @Test
    public void immutableListRoundTripsOnJavaSeventeen() throws Exception {
        // 不可变列表无需 --add-opens 即可编码。
        List<String> values = List.of("first", "second");
        Object decoded = this.roundTrip(values, List.class);

        // 接收方只依赖公开的容器签名。
        assertEquals(values, decoded);
    }

    @Test
    public void immutableSetRetainsSetSemantics() throws Exception {
        // Set 的不可变实现同样走容器编码。
        Set<String> values = Set.of("first", "second");
        Object decoded = this.roundTrip(values, Set.class);

        // 返回值保留集合语义与完整成员。
        assertTrue(decoded instanceof Set<?>);
        assertEquals(values, decoded);
    }

    @Test
    public void immutableMapRetainsEntries() throws Exception {
        // 不可变 Map 编码保留每个键和值。
        Map<String, Integer> values = Map.of("first", 1, "second", 2);
        Object decoded = this.roundTrip(values, Map.class);
        assertEquals(values, decoded);
    }

    @Test
    public void nestedImmutableContainersRoundTrip() throws Exception {
        // 嵌套不可变容器的元素继续使用同一序列化工厂。
        Map<String, List<Set<Integer>>> values = Map.of("nested", List.of(Set.of(1, 2)));
        Object decoded = this.roundTrip(values, Map.class);

        // 所有容器层级均可通过其公开类型恢复。
        assertEquals(values, decoded);
    }

    @Test
    public void ordinaryStringUsesNativeScalarSerializer() throws Exception {
        // 非容器类型保持原生 Hessian 语义。
        Object decoded = this.roundTrip("ordinary", String.class);
        assertEquals("ordinary", decoded);
    }

    @Test
    public void primitiveArrayUsesNativeArraySerializer() throws Exception {
        // 基本类型数组不能被误判为 Collection。
        int[] values = { 1, 2, 3 };
        int[] decoded = (int[]) this.roundTrip(values, int[].class);
        assertArrayEquals(values, decoded);
    }

    @Test
    public void collectionInterfaceSelectsContainerSerializer() throws Exception {
        // 接口签名本身也属于支持的容器类型。
        DubboHessianSerializerFactory factory = new DubboHessianSerializerFactory(this.getClass().getClassLoader());
        Serializer serializer = factory.getSerializer(Collection.class);
        assertTrue(serializer instanceof CollectionSerializer);
    }

    private Object roundTrip(Object value, Class<?> type) throws Exception {
        // 出站使用被测工厂，接收侧使用独立 Hessian 输入。
        DubboHessianSerializerFactory factory = new DubboHessianSerializerFactory(this.getClass().getClassLoader());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.setSerializerFactory(factory);
        output.writeObject(value);
        output.flush();

        // 读取公开声明类型，避免依赖发送端 JDK 私有容器实现。
        byte[] document = bytes.toByteArray();
        DubboHessianValueGuard.validate(document);
        Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(document));
        return input.readObject(type);
    }
}