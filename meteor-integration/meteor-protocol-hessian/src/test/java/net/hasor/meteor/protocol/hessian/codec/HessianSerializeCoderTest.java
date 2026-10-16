/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.codec;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import net.hasor.example.protocol.hessian.HessianPayload;
import net.hasor.meteor.serialize.SerializeCoder;
import net.hasor.meteor.serialize.SerializeFactory;
import org.junit.Test;
import static org.junit.Assert.*;

public class HessianSerializeCoderTest {
    private final SerializeCoder coder = new HessianSerializeCoder();

    private Object roundTrip(Object value, Class<?> type) throws IOException {
        return this.coder.decode(this.coder.encode(value), type);
    }

    @Test
    public void nullValueRoundTrips() throws Exception {
        // 明确编码一个 null 值，再按同一编码格式读取。
        assertNull(this.roundTrip(null, Object.class));
    }

    @Test
    public void missingBytesDecodeToNull() throws Exception {
        // 没有报文字节时返回空值。
        assertNull(this.coder.decode(null, Object.class));
    }

    @Test
    public void unicodeAndEmptyStrings() throws Exception {
        // 不同内容都验证同一个字符串往返契约。
        for (String value : Arrays.asList("", "你好，Meteor 🚀", "line\n\t\"\\\u0000")) {
            assertEquals(value, this.roundTrip(value, String.class));
        }
    }

    @Test
    public void integerParameterRoundTrips() throws Exception {
        // 整型参数按 primitive 目标类型读取。
        assertEquals(42, this.roundTrip(42, int.class));
    }

    @Test
    public void booleanParameterRoundTrips() throws Exception {
        // 布尔参数按 primitive 目标类型读取。
        assertEquals(true, this.roundTrip(true, boolean.class));
    }

    @Test
    public void doubleParameterRoundTrips() throws Exception {
        // 浮点参数往返保留精度。
        assertEquals(1.25d, this.roundTrip(1.25d, double.class));
    }

    @Test
    public void maximumLongParameterRoundTrips() throws Exception {
        // 最大长整型不应被截断。
        assertEquals(Long.MAX_VALUE, this.roundTrip(Long.MAX_VALUE, long.class));
    }

    @Test
    public void byteArrayRoundTrips() throws Exception {
        // 二进制数组保留有符号字节。
        byte[] value = { 0, -1, 42 };
        assertArrayEquals(value, (byte[]) this.roundTrip(value, byte[].class));
    }

    @Test
    public void integerArrayRoundTrips() throws Exception {
        // 整型数组保留元素及顺序。
        int[] value = { 1, -2, 3 };
        assertArrayEquals(value, (int[]) this.roundTrip(value, int[].class));
    }

    @Test
    public void stringArrayPreservesNullAndEmptyElements() throws Exception {
        // 字符串数组区分空元素和空字符串。
        String[] value = { "中文", null, "" };
        assertArrayEquals(value, (String[]) this.roundTrip(value, String[].class));
    }

    @Test
    public void listPreservesNullAndOrder() throws Exception {
        // 列表往返保留空元素及原始次序。
        List<String> list = new ArrayList<>(Arrays.asList("中文", null, "tail"));
        assertEquals(list, this.roundTrip(list, ArrayList.class));
    }

    @Test
    public void nestedMapPreservesCollections() throws Exception {
        // 映射同时包含列表、标量和空值。
        List<String> list = Arrays.asList("中文", null, "tail");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("items", list);
        source.put("count", 3);
        source.put("empty", null);

        Map<?, ?> result = (Map<?, ?>) this.roundTrip(source, LinkedHashMap.class);
        assertEquals(list, result.get("items"));
        assertEquals(3, result.get("count"));
        assertNull(result.get("empty"));
    }

    @Test
    public void beanParameterAndResult() throws Exception {
        // 业务 DTO 往返生成独立对象并保留字段。
        HessianPayload value = new HessianPayload();
        value.setName("业务参数 🚀");
        value.setCount(7);
        HessianPayload decoded = (HessianPayload) this.roundTrip(value, HessianPayload.class);
        assertEquals(value.getName(), decoded.getName());
        assertEquals(value.getCount(), decoded.getCount());
        assertNotSame(value, decoded);
    }

    @Test
    public void invalidMarkerDoesNotPoisonCoder() throws Exception {
        // 非法报文按 IOException 失败，后续独立消息仍可正常处理。
        byte[] malformed = { (byte) 0xff, 1, 0 };
        assertThrows(IOException.class, () -> this.coder.decode(malformed, HessianPayload.class));
        assertEquals("next request", this.roundTrip("next request", String.class));
    }

    @Test
    public void truncatedMarkerDoesNotPoisonCoder() throws Exception {
        // 截断的报文失败不能污染编码器后续使用。
        byte[] malformed = { (byte) 0xff };
        assertThrows(IOException.class, () -> this.coder.decode(malformed, HessianPayload.class));
        assertEquals("next request", this.roundTrip("next request", String.class));
    }

    @Test
    public void independentMessagesDoNotShareReferences() throws Exception {
        // 每次编码独立成帧，不依赖前一次消息的引用表。
        String first = "repeated value";
        byte[] encoded = this.coder.encode(first);
        assertArrayEquals(encoded, this.coder.encode(first));
        assertEquals(first, this.coder.decode(encoded, String.class));
        assertEquals(first, this.coder.decode(encoded, String.class));
    }

    @Test
    public void sharedCoderSupportsConcurrentCalls() throws Exception {
        // 多个线程共用同一个编码器，负载包含字符串和 DTO。
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int worker = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int j = 0; j < 30; j++) {
                        String value = worker + ":" + j + "中文";
                        assertEquals(value, this.roundTrip(value, String.class));
                        HessianPayload payload = new HessianPayload();
                        payload.setName(value);
                        payload.setCount(j);
                        HessianPayload decoded = (HessianPayload) this.roundTrip(payload, HessianPayload.class);
                        assertEquals(value, decoded.getName());
                        assertEquals(j, decoded.getCount());
                    }
                    return null;
                }));
            }
            // 首批四个工作线程就绪后统一起跑，其余任务继续复用相同编码器。
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            // 有界等待所有往返完成，让工作线程断言传回主测试。
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void readsAndWritesLegacyStringFixture() throws Exception {
        // 固定 Hessian 1 报文验证与原 RSF 编码器的线格式兼容。
        byte[] fixture = { 'S', 0, 5, 'h', 'e', 'l', 'l', 'o' };
        assertEquals("hello", this.coder.decode(fixture, String.class));
        assertArrayEquals(fixture, this.coder.encode("hello"));
    }

    @Test
    public void spiRegistersHessianWithoutContainer() throws Exception {
        // 通过真实 SPI 工厂取得 Hessian，独立完成一次编码与解码。
        SerializeFactory factory = SerializeFactory.createFactory();
        SerializeCoder coder = factory.getSerializeCoder("Hessian");
        assertNotNull(coder);

        // 插件按名称注册，查找支持大小写并保留 Hessian 编码格式。
        assertEquals("Hessian", coder.name());
        assertSame(coder, factory.getSerializeCoder("hessian"));
        assertSame(coder, factory.getSerializeCoder("HESSIAN"));
        byte[] encoded = coder.encode("hello");
        assertEquals("hello", coder.decode(encoded, String.class));
    }

    @Test
    public void spiNameMatchingIsIndependentOfDefaultLocale() {
        // 先用默认语言发现编码器，再切换至特殊大小写规则的土耳其语。
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            SerializeFactory factory = SerializeFactory.createFactory();
            SerializeCoder coder = factory.getSerializeCoder("Hessian");
            assertNotNull(coder);

            // 注册名称必须继续匹配同一个 Hessian 实例。
            Locale.setDefault(new Locale("tr", "TR"));
            assertSame(coder, factory.getSerializeCoder("HESSIAN"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
