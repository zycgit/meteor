/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.serialize;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.meteor.serialize.SerializeCoderTest.Payload;
import net.hasor.meteor.serialize.coder.JsonSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonSerializeCoderTest {
    private final SerializeCoder coder = new JsonSerializeCoder();

    @Test
    public void unknownFieldsAndMissingFieldsAllowServiceDtoEvolution() throws Exception {
        Payload decoded = (Payload) this.coder.decode("{\"name\":\"中文🙂\",\"futureField\":{\"value\":1}}".getBytes(StandardCharsets.UTF_8), Payload.class);
        assertEquals("中文🙂", decoded.getName());
        assertEquals(0, decoded.getCount());
        assertEquals("\"中文\"", new String(this.coder.encode("中文"), StandardCharsets.UTF_8));
    }

    @Test
    public void nullPropertiesAndMapEntriesRemainOmitted() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("present", "value");
        values.put("missing", null);
        assertEquals("{\"present\":\"value\"}", new String(this.coder.encode(values), StandardCharsets.UTF_8));
        Map<?, ?> bean = (Map<?, ?>) this.coder.decode(this.coder.encode(new Payload()), Map.class);
        assertFalse(bean.containsKey("name"));
        assertEquals(0, bean.get("count"));
        assertEquals("{}", new String(this.coder.encode(new Object()), StandardCharsets.UTF_8));
    }

    @Test
    public void genericBeanPropertiesAndEpochDatesKeepTheirTypes() throws Exception {
        Payload child = new Payload();
        child.setName("child");
        child.setCount(3);
        Envelope source = new Envelope();
        source.items = Arrays.asList(child, null);
        source.nested = Collections.singletonMap("children", Collections.singletonList(child));
        source.created = new Date(123456789L);
        byte[] encoded = this.coder.encode(source);
        Envelope restored = (Envelope) this.coder.decode(encoded, Envelope.class);
        assertEquals("child", restored.items.get(0).getName());
        assertNull(restored.items.get(1));
        assertEquals(3, restored.nested.get("children").get(0).getCount());
        assertEquals(source.created, restored.created);
        assertTrue(new String(encoded, StandardCharsets.UTF_8).contains("\"created\":123456789"));
    }

    @Test
    public void typeMetadataNeverChoosesTheDeserializedClass() throws Exception {
        byte[] json = "{\"@type\":\"not.a.real.Class\",\"name\":\"safe\",\"count\":2}".getBytes(StandardCharsets.UTF_8);
        Payload value = (Payload) this.coder.decode(json, Payload.class);
        assertEquals("safe", value.getName());
        Object generic = this.coder.decode(json, Object.class);
        assertTrue(generic instanceof Map);
        assertEquals("not.a.real.Class", ((Map<?, ?>) generic).get("@type"));
    }

    @Test
    public void emptyTruncatedAndMultipleDocumentsFailWithoutPoisoningTheCoder() throws Exception {
        for (String json : Arrays.asList("", " ", "{", "{\"name\":}", "{} {}", "null {}", "42 true")) {
            try {
                this.coder.decode(json.getBytes(StandardCharsets.UTF_8), Object.class);
                fail("Invalid JSON must fail: " + json);
            } catch (IOException expected) {
                assertEquals("next", this.coder.decode(this.coder.encode("next"), String.class));
            }
        }
        try {
            this.coder.decode(new byte[] { '"', (byte) 0xc3, '"' }, String.class);
            fail("Malformed UTF-8 must fail");
        } catch (IOException expected) {
        }
    }

    @Test
    public void beanGetterFailuresUseTheCoderIOExceptionContract() throws Exception {
        try {
            this.coder.encode(new BrokenBean());
            fail("An unreadable bean must not be silently encoded");
        } catch (IOException expected) {
            assertNotNull(expected.getCause());
        }
        assertEquals("next", this.coder.decode(this.coder.encode("next"), String.class));
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
