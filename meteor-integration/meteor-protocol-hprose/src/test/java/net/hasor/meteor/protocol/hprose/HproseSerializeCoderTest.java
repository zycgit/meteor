/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.serialize.SerializeCoder;
import net.hasor.meteor.serialize.SerializeFactory;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseSerializeCoderTest {
    private final SerializeCoder coder = new HproseSerializeCoder();

    @Test
    public void protocolModuleRegistersCoderThroughSpi() throws Exception {
        SerializeFactory factory = SerializeFactory.createFactory(this.getClass().getClassLoader());
        SerializeCoder discovered = factory.getSerializeCoder("hprose");
        assertTrue(discovered instanceof HproseSerializeCoder);

        // 序列化器仅由插件提供，SPI 大小写查询仍返回同一个实例。
        Class<?> type = discovered.getClass();
        String resource = type.getName().replace('.', '/') + ".class";
        List<URL> definitions = Collections.list(this.getClass().getClassLoader().getResources(resource));
        assertEquals(1, definitions.size());
        URL plugin = HproseProtocol.class.getProtectionDomain().getCodeSource().getLocation();
        URL framework = SerializeCoder.class.getProtectionDomain().getCodeSource().getLocation();
        URL coderLocation = type.getProtectionDomain().getCodeSource().getLocation();
        assertEquals(plugin, coderLocation);
        assertNotEquals(framework, coderLocation);
        assertEquals("Hprose", discovered.name());
        assertSame(discovered, factory.getSerializeCoder("HPROSE"));
        assertEquals("hello", discovered.decode(discovered.encode("hello"), String.class));
        assertNotNull(factory.getSerializeCoder("Java"));
    }

    private Object roundTrip(Object value, Class<?> type) throws IOException {
        return this.coder.decode(this.coder.encode(value), type);
    }

    @Test
    public void nullValueAndMissingBytes() throws Exception {
        assertNull(this.roundTrip(null, Object.class));
        assertNull(this.coder.decode(null, Object.class));
    }

    @Test
    public void unicodeAndEmptyStrings() throws Exception {
        for (String value : Arrays.asList("", "你好，Meteor 🚀", "line\n\t\"\\\u0000")) {
            assertEquals(value, this.roundTrip(value, String.class));
        }
    }

    @Test
    public void primitiveParameters() throws Exception {
        assertEquals(42, this.roundTrip(42, int.class));
        assertEquals(true, this.roundTrip(true, boolean.class));
        assertEquals(1.25d, this.roundTrip(1.25d, double.class));
        assertEquals(Long.MAX_VALUE, this.roundTrip(Long.MAX_VALUE, long.class));
    }

    @Test
    public void primitiveAndObjectArrays() throws Exception {
        byte[] bytes = { 0, -1, 42 };
        assertArrayEquals(bytes, (byte[]) this.roundTrip(bytes, byte[].class));
        int[] numbers = { 1, -2, 3 };
        assertArrayEquals(numbers, (int[]) this.roundTrip(numbers, int[].class));
        String[] strings = { "中文", null, "" };
        assertArrayEquals(strings, (String[]) this.roundTrip(strings, String[].class));
    }

    @Test
    public void collectionsAndNestedValues() throws Exception {
        List<String> list = new ArrayList<>(Arrays.asList("中文", null, "tail"));
        assertEquals(list, this.roundTrip(list, ArrayList.class));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", list);
        map.put("count", 3);
        map.put("empty", null);
        Map<?, ?> result = (Map<?, ?>) this.roundTrip(map, LinkedHashMap.class);
        assertEquals(list, result.get("items"));
        assertEquals(3, result.get("count"));
        assertNull(result.get("empty"));
    }

    @Test
    public void beanParameterAndResult() throws Exception {
        Payload value = new Payload();
        value.setName("业务参数 🚀");
        value.setCount(7);
        Payload decoded = (Payload) this.roundTrip(value, Payload.class);
        assertEquals(value.getName(), decoded.getName());
        assertEquals(value.getCount(), decoded.getCount());
        assertNotSame(value, decoded);
    }

    @Test
    public void malformedDataFailsWithIOException() throws Exception {
        byte[] malformed = { (byte) 0xff, 1, 0 };
        assertThrows(IOException.class, () -> this.coder.decode(malformed, Payload.class));
    }

    @Test
    public void failedDecodeDoesNotPoisonCoder() throws Exception {
        // 单次解码失败不污染序列化器，后续消息使用独立状态。
        assertThrows(IOException.class, () -> this.coder.decode(new byte[] { (byte) 0xff }, Object.class));
        assertEquals("next request", this.roundTrip("next request", String.class));
    }

    @Test
    public void independentMessagesDoNotShareReferences() throws Exception {
        String first = "repeated value";
        byte[] encoded = this.coder.encode(first);
        assertArrayEquals(encoded, this.coder.encode(first));
        assertEquals(first, this.coder.decode(encoded, String.class));
        assertEquals(first, this.coder.decode(encoded, String.class));
    }

    @Test
    public void sharedCoderSupportsConcurrentCalls() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            // 所有任务共用同一个序列化器，各自的引用表与对象状态必须独立。
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int worker = i;
                Future<?> task = executor.submit(() -> {
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
    public void readsAndWritesLegacyStringFixture() throws Exception {
        byte[] fixture = "s5\"hello\"".getBytes(StandardCharsets.UTF_8);
        assertEquals("hello", this.coder.decode(fixture, String.class));
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
