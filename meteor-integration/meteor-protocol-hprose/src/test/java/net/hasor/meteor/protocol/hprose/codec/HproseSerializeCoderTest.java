/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.codec;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.protocol.hprose.session.HproseProtocol;
import net.hasor.meteor.serialize.SerializeCoder;
import net.hasor.meteor.serialize.SerializeFactory;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseSerializeCoderTest {
    private final SerializeCoder coder = new HproseSerializeCoder();

    @Test
    public void protocolModuleRegistersCoderThroughSpi() throws Exception {
        // 通过插件 SPI 发现序列化器实现。
        SerializeFactory factory = SerializeFactory.createFactory(this.getClass().getClassLoader());
        SerializeCoder discovered = factory.getSerializeCoder("hprose");
        assertTrue(discovered instanceof HproseSerializeCoder);
    }

    @Test
    public void registeredCoderUsesHproseName() throws Exception {
        // SPI 名称保持为对外配置使用的 Hprose。
        SerializeFactory factory = SerializeFactory.createFactory(this.getClass().getClassLoader());
        SerializeCoder discovered = factory.getSerializeCoder("hprose");
        assertEquals("Hprose", discovered.name());
    }

    @Test
    public void serializeFactoryFindsHproseIgnoringCase() throws Exception {
        // 配置查找不区分序列化器名称大小写。
        SerializeFactory factory = SerializeFactory.createFactory(this.getClass().getClassLoader());
        SerializeCoder discovered = factory.getSerializeCoder("hprose");
        assertSame(discovered, factory.getSerializeCoder("HPROSE"));
    }

    @Test
    public void registeredCoderRoundTripsAString() throws Exception {
        // SPI 实例能完成一次独立的字符串往返。
        SerializeFactory factory = SerializeFactory.createFactory(this.getClass().getClassLoader());
        SerializeCoder discovered = factory.getSerializeCoder("hprose");
        byte[] encoded = discovered.encode("hello");
        assertEquals("hello", discovered.decode(encoded, String.class));
    }

    @Test
    public void hproseCoderHasASingleOwnerInThePlugin() throws Exception {
        // 先从 SPI 取得实现，再检查其类资源确实只由协议插件提供。
        SerializeFactory factory = SerializeFactory.createFactory(this.getClass().getClassLoader());
        SerializeCoder discovered = factory.getSerializeCoder("hprose");
        assertTrue(discovered instanceof HproseSerializeCoder);

        // 类资源只能找到一份，且来源位置与插件一致、与内核不同。
        Class<?> type = discovered.getClass();
        String resource = type.getName().replace('.', '/') + ".class";
        Enumeration<URL> resources = this.getClass().getClassLoader().getResources(resource);
        List<URL> definitions = Collections.list(resources);
        assertEquals(1, definitions.size());
        URL plugin = HproseProtocol.class.getProtectionDomain().getCodeSource().getLocation();
        URL framework = SerializeCoder.class.getProtectionDomain().getCodeSource().getLocation();
        URL coderLocation = type.getProtectionDomain().getCodeSource().getLocation();
        assertEquals(plugin, coderLocation);
        assertNotEquals(framework, coderLocation);
    }

    private Object roundTrip(Object value, Class<?> type) throws IOException {
        return this.coder.decode(this.coder.encode(value), type);
    }

    @Test
    public void roundTripsNullValue() throws Exception {
        // null 编码再解码后仍为空，不创建占位对象。
        assertNull(this.roundTrip(null, Object.class));
    }

    @Test
    public void missingBytesDecodeAsNull() throws Exception {
        // 缺失字节输入保持序列化器的空值约定。
        assertNull(this.coder.decode(null, Object.class));
    }

    @Test
    public void emptyStringRoundTrips() throws Exception {
        // 空字符串与 null 区分，往返后保持原值。
        String value = "";
        assertEquals(value, this.roundTrip(value, String.class));
    }

    @Test
    public void unicodeStringRoundTrips() throws Exception {
        // 中文和补充字符在字符串往返后不得损坏。
        String value = "你好，Meteor 🚀";
        assertEquals(value, this.roundTrip(value, String.class));
    }

    @Test
    public void escapedStringRoundTrips() throws Exception {
        // 控制字符与转义字符保留原始字符内容。
        String value = "line\n\t\"\\\u0000";
        assertEquals(value, this.roundTrip(value, String.class));
    }

    @Test
    public void integerParameterRoundTrips() throws Exception {
        // 按 int 参数声明恢复整数值。
        assertEquals(42, this.roundTrip(42, int.class));
    }

    @Test
    public void booleanParameterRoundTrips() throws Exception {
        // 按 boolean 参数声明恢复布尔值。
        assertEquals(true, this.roundTrip(true, boolean.class));
    }

    @Test
    public void doubleParameterRoundTrips() throws Exception {
        // 按 double 参数声明恢复小数值。
        assertEquals(1.25d, this.roundTrip(1.25d, double.class));
    }

    @Test
    public void maximumLongParameterRoundTrips() throws Exception {
        // 长整型上界不能在往返中发生精度或范围丢失。
        assertEquals(Long.MAX_VALUE, this.roundTrip(Long.MAX_VALUE, long.class));
    }

    @Test
    public void byteArrayRoundTrips() throws Exception {
        // 二进制数组中的有符号字节保持不变。
        byte[] value = { 0, -1, 42 };
        assertArrayEquals(value, (byte[]) this.roundTrip(value, byte[].class));
    }

    @Test
    public void integerArrayRoundTrips() throws Exception {
        // 基本类型数组保留顺序与负值。
        int[] value = { 1, -2, 3 };
        assertArrayEquals(value, (int[]) this.roundTrip(value, int[].class));
    }

    @Test
    public void objectArrayRoundTrips() throws Exception {
        // 对象数组保留 Unicode、null 和空字符串的区别。
        String[] value = { "中文", null, "" };
        assertArrayEquals(value, (String[]) this.roundTrip(value, String[].class));
    }

    @Test
    public void listWithNullRoundTrips() throws Exception {
        // 列表元素的顺序与空值在往返后保持一致。
        List<String> list = new ArrayList<>(Arrays.asList("中文", null, "tail"));
        assertEquals(list, this.roundTrip(list, ArrayList.class));
    }

    @Test
    public void mapWithNestedListRoundTrips() throws Exception {
        // 构造包含嵌套列表、数字和空值的有序映射。
        List<String> list = new ArrayList<>(Arrays.asList("中文", null, "tail"));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", list);
        map.put("count", 3);
        map.put("empty", null);

        // 解码后逐项检查结构，嵌套列表不应被转成字符串。
        Map<?, ?> result = (Map<?, ?>) this.roundTrip(map, LinkedHashMap.class);
        assertEquals(list, result.get("items"));
        assertEquals(3, result.get("count"));
        assertNull(result.get("empty"));
    }

    @Test
    public void beanRoundTripPreservesItsFields() throws Exception {
        // 准备业务 Bean，覆盖不同类型的属性。
        Payload value = new Payload();
        value.setName("业务参数 🚀");
        value.setCount(7);

        // 恢复独立 Bean 实例，属性值与原对象一致。
        Payload decoded = (Payload) this.roundTrip(value, Payload.class);
        assertEquals(value.getName(), decoded.getName());
        assertEquals(value.getCount(), decoded.getCount());
        assertNotSame(value, decoded);
    }

    @Test
    public void malformedDataFailsWithIOException() throws Exception {
        // 无效类型标签应统一报告为解码 IO 异常。
        byte[] malformed = { (byte) 0xff, 1, 0 };
        assertThrows(IOException.class, () -> this.coder.decode(malformed, Payload.class));
    }

    @Test
    public void failedDecodeDoesNotPoisonCoder() throws Exception {
        // 先提交损坏报文触发一次解码失败。
        byte[] malformed = { (byte) 0xff };
        assertThrows(IOException.class, () -> this.coder.decode(malformed, Object.class));

        // 后续消息使用独立引用状态，不被先前的失败污染。
        assertEquals("next request", this.roundTrip("next request", String.class));
    }

    @Test
    public void independentEncodesDoNotShareReferences() throws Exception {
        // 同一值重复编码时，各条消息的引用表互不共享。
        String first = "repeated value";
        byte[] encoded = this.coder.encode(first);
        assertArrayEquals(encoded, this.coder.encode(first));
    }

    @Test
    public void independentDecodesDoNotShareReferences() throws Exception {
        // 同一报文重复解码时，各条消息的引用表互不共享。
        String first = "repeated value";
        byte[] encoded = this.coder.encode(first);
        assertEquals(first, this.coder.decode(encoded, String.class));
        assertEquals(first, this.coder.decode(encoded, String.class));
    }

    @Test
    public void sharedCoderSupportsConcurrentCalls() throws Exception {
        // 使用多个工作线程并发复用同一个序列化器实例。
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            // 所有任务共用同一个序列化器，各自的引用表与对象状态必须独立。
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int worker = i;
                Future<?> task = executor.submit(() -> {
                    // 每个任务重复编码不同值，验证并发调用没有共享引用状态。
                    for (int j = 0; j < 30; j++) {
                        String value = worker + ":" + j + "中文";
                        assertEquals(value, this.roundTrip(value, String.class));
                        Payload payload = new Payload();
                        payload.setName(value);
                        payload.setCount(j);
                        Payload decoded = (Payload) this.roundTrip(payload, Payload.class);
                        assertEquals(value, decoded.getName());
                        assertEquals(j, decoded.getCount());
                    }
                    return null;
                });
                futures.add(task);
            }

            // 等待每个任务完成，工作线程内的断言失败必须传回测试线程。
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void readsLegacyStringFixture() throws Exception {
        // 固定 Hprose 字符串报文保持读取兼容性。
        byte[] fixture = "s5\"hello\"".getBytes(StandardCharsets.UTF_8);
        assertEquals("hello", this.coder.decode(fixture, String.class));
    }

    @Test
    public void writesLegacyStringFixture() throws Exception {
        // 字符串写出格式与历史 Hprose 报文保持兼容。
        byte[] fixture = "s5\"hello\"".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(fixture, this.coder.encode("hello"));
    }

    public static class Payload implements Serializable {
        private static final long   serialVersionUID = 1L;
        private              String name;
        private              int    count;

        public Payload() {
        }

        public String getName() {
            return this.name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getCount() {
            return this.count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }
}
