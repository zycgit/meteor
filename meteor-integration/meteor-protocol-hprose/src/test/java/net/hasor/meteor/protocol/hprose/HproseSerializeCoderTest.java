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
        Class<?> type = discovered.getClass();
        assertEquals(1, Collections.list(this.getClass().getClassLoader().getResources(type.getName().replace('.', '/') + ".class")).size());
        assertEquals(HproseProtocol.class.getProtectionDomain().getCodeSource().getLocation(), type.getProtectionDomain().getCodeSource().getLocation());
        assertNotEquals(SerializeCoder.class.getProtectionDomain().getCodeSource().getLocation(), type.getProtectionDomain().getCodeSource().getLocation());
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
        assertNull(roundTrip(null, Object.class));
        assertNull(this.coder.decode(null, Object.class));
    }

    @Test
    public void unicodeAndEmptyStrings() throws Exception {
        for (String value : Arrays.asList("", "你好，Meteor 🚀", "line\n\t\"\\\u0000")) {
            assertEquals(value, roundTrip(value, String.class));
        }
    }

    @Test
    public void primitiveParameters() throws Exception {
        assertEquals(42, roundTrip(42, int.class));
        assertEquals(true, roundTrip(true, boolean.class));
        assertEquals(1.25d, roundTrip(1.25d, double.class));
        assertEquals(Long.MAX_VALUE, roundTrip(Long.MAX_VALUE, long.class));
    }

    @Test
    public void primitiveAndObjectArrays() throws Exception {
        assertArrayEquals(new byte[] { 0, -1, 42 }, (byte[]) roundTrip(new byte[] { 0, -1, 42 }, byte[].class));
        assertArrayEquals(new int[] { 1, -2, 3 }, (int[]) roundTrip(new int[] { 1, -2, 3 }, int[].class));
        assertArrayEquals(new String[] { "中文", null, "" }, (String[]) roundTrip(new String[] { "中文", null, "" }, String[].class));
    }

    @Test
    public void collectionsAndNestedValues() throws Exception {
        List<String> list = new ArrayList<>(Arrays.asList("中文", null, "tail"));
        assertEquals(list, roundTrip(list, ArrayList.class));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", list);
        map.put("count", 3);
        map.put("empty", null);
        Map<?, ?> result = (Map<?, ?>) roundTrip(map, LinkedHashMap.class);
        assertEquals(list, result.get("items"));
        assertEquals(3, result.get("count"));
        assertNull(result.get("empty"));
    }

    @Test
    public void beanParameterAndResult() throws Exception {
        Payload value = new Payload();
        value.setName("业务参数 🚀");
        value.setCount(7);
        Payload decoded = (Payload) roundTrip(value, Payload.class);
        assertEquals(value.getName(), decoded.getName());
        assertEquals(value.getCount(), decoded.getCount());
        assertNotSame(value, decoded);
    }

    @Test
    public void malformedDataFailsWithIOException() throws Exception {
        try {
            this.coder.decode(new byte[] { (byte) 0xff, 1, 0 }, Payload.class);
            fail("Malformed Hprose must fail");
        } catch (IOException expected) {
            // The caller can handle a format failure without knowing the backend library.
        }
    }

    @Test
    public void failedDecodeDoesNotPoisonCoder() throws Exception {
        try {
            this.coder.decode(new byte[] { (byte) 0xff }, Object.class);
            fail("Malformed input must fail");
        } catch (IOException expected) {
            assertEquals("next request", roundTrip("next request", String.class));
        }
    }

    @Test
    public void independentMessagesDoNotShareReferences() throws Exception {
        String first = new String("repeated value");
        byte[] encoded = this.coder.encode(first);
        assertArrayEquals(encoded, this.coder.encode(first));
        assertEquals(first, this.coder.decode(encoded, String.class));
        assertEquals(first, this.coder.decode(encoded, String.class));
    }

    @Test
    public void sharedCoderSupportsConcurrentCalls() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int worker = i;
                futures.add(executor.submit(() -> {
                    for (int j = 0; j < 30; j++) {
                        String value = worker + ":" + j + "中文";
                        assertEquals(value, roundTrip(value, String.class));
                        Payload payload = new Payload();
                        payload.setName(value);
                        payload.setCount(j);
                        Payload decoded = (Payload) roundTrip(payload, Payload.class);
                        assertEquals(value, decoded.getName());
                        assertEquals(j, decoded.getCount());
                    }
                    return null;
                }));
            }
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
