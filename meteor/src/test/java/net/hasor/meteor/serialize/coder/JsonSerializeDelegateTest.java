/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.serialize.coder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonSerializeDelegateTest {
    @Test
    public void delegateCanUseAnotherLibraryAndCustomDateFormat() throws Exception {
        // 用户委托使用 Gson 的配置，仍可产生标准 JSON 文档。
        JsonSerializeCoder coder = new JsonSerializeCoder().setDelegate(new GsonDelegate());
        Payload value = new Payload();
        value.name = "中文";
        value.created = new Date(0);

        byte[] bytes = coder.encode(value);
        Payload restored = (Payload) coder.decode(bytes, Payload.class);
        Map<?, ?> document = new ObjectMapper().readValue(bytes, Map.class);
        assertEquals("中文", restored.name);
        assertEquals(value.created, restored.created);
        assertTrue(document.get("created") instanceof String);
        assertTrue(document.containsKey("optional"));
    }

    @Test
    public void delegateFailuresRetainTheCoderIOExceptionContract() {
        // 第三方库的运行异常由编码器转换为有原因链的 I/O 异常。
        JsonSerializeCoder coder = new JsonSerializeCoder().setDelegate(new GsonDelegate());
        IOException failure = assertThrows(IOException.class, () -> //
                coder.decode("{".getBytes(StandardCharsets.UTF_8), Payload.class));
        assertNotNull(failure.getCause());
    }

    private static final class GsonDelegate implements JsonSerializeDelegate {
        private final Gson gson = new GsonBuilder().serializeNulls().setDateFormat("yyyy-MM-dd HH:mm:ss Z").create();

        @Override
        public byte[] encode(Object value) {
            return this.gson.toJson(value).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Object decode(byte[] json, Class<?> type) {
            return this.gson.fromJson(new String(json, StandardCharsets.UTF_8), type);
        }
    }

    public static final class Payload {
        public String name;
        public Date   created;
        public String optional;
    }
}
