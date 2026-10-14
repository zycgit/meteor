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
import net.hasor.meteor.unit.serialize.support.Payload;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.serialize.coder.HessianSerializeCoder;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import net.hasor.meteor.serialize.coder.JsonSerializeCoder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class SerializeCoderTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> coders() {
        List<Object[]> coders = new ArrayList<>();
        coders.add(new Object[] { "Java", new JavaSerializeCoder() });
        coders.add(new Object[] { "Json", new JsonSerializeCoder() });
        coders.add(new Object[] { "Hessian", new HessianSerializeCoder() });
        return coders;
    }

    private final String         name;
    private final SerializeCoder coder;

    public SerializeCoderTest(String name, SerializeCoder coder) {
        this.name = name;
        this.coder = coder;
    }

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
        // 没有报文字节时各编码器返回空值。
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
        // JSON 可以省略空值键，但查询结果仍应为空。
        assertNull(result.get("empty"));
    }

    @Test
    public void beanParameterAndResult() throws Exception {
        // 业务 DTO 往返生成独立对象并保留字段。
        Payload value = new Payload();
        value.setName("业务参数 🚀");
        value.setCount(7);
        Payload decoded = (Payload) this.roundTrip(value, Payload.class);
        assertEquals(value.getName(), decoded.getName());
        assertEquals(value.getCount(), decoded.getCount());
        assertNotSame(value, decoded);
    }

    @Test
    public void invalidMarkerDoesNotPoisonCoder() throws Exception {
        // 非法报文按 IOException 失败，后续独立消息仍可正常处理。
        byte[] malformed = { (byte) 0xff, 1, 0 };
        assertThrows(IOException.class, () -> this.coder.decode(malformed, Payload.class));
        assertEquals("next request", this.roundTrip("next request", String.class));
    }

    @Test
    public void truncatedMarkerDoesNotPoisonCoder() throws Exception {
        // 截断的报文失败不能污染编码器后续使用。
        byte[] malformed = { (byte) 0xff };
        assertThrows(IOException.class, () -> this.coder.decode(malformed, Payload.class));
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
                        Payload payload = new Payload();
                        payload.setName(value);
                        payload.setCount(j);
                        Payload decoded = (Payload) this.roundTrip(payload, Payload.class);
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
        // 当前参数只选择一种编码器，固定报文验证线格式兼容。
        byte[] fixture;
        switch (this.name) {
            case "Java":
                fixture = new byte[] { (byte) 0xac, (byte) 0xed, 0, 5, 0x74, 0, 5, 'h', 'e', 'l', 'l', 'o' };
                break;
            case "Json":
                fixture = "\"hello\"".getBytes(StandardCharsets.UTF_8);
                break;
            case "Hessian":
                fixture = new byte[] { 'S', 0, 5, 'h', 'e', 'l', 'l', 'o' };
                break;
            default:
                throw new AssertionError(this.name);
        }
        assertEquals("hello", this.coder.decode(fixture, String.class));
        assertArrayEquals(fixture, this.coder.encode("hello"));
    }

}
