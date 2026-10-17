/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.serialize;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.serialize.coder.JsonSerializeCoder;
import net.hasor.meteor.serialize.coder.JsonSerializeDelegate;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class JsonDelegateScenarioTest {
    @Test
    public void delegateBelongsOnlyToTheConfiguredContext() throws Exception {
        // 经正式入口获取编码器，仅为一个 Context 替换 JSON 序列化实现。
        Gson gson = new GsonBuilder().serializeNulls().create();
        try (MetContext first = new MetConfiguration().buildContext(); //
             MetContext second = new MetConfiguration().buildContext()) {
            JsonSerializeCoder coder = (JsonSerializeCoder) first.getSerializeCoder("Json");
            coder.setDelegate(new JsonSerializeDelegate() {
                @Override
                public byte[] encode(Object value) {
                    return gson.toJson(value).getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public Object decode(byte[] json, Class<?> type) {
                    return gson.fromJson(new String(json, StandardCharsets.UTF_8), type);
                }
            });
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("missing", null);

            // 自定义空值规则不影响另一 Context。
            assertEquals("{\"missing\":null}", new String(coder.encode(value), StandardCharsets.UTF_8));
            byte[] ordinary = second.getSerializeCoder("Json").encode(value);
            assertEquals("{}", new String(ordinary, StandardCharsets.UTF_8));
        }
    }
}
