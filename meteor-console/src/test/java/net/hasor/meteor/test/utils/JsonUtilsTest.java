/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test.utils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import net.hasor.meteor.console.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class JsonUtilsTest {
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {
    };

    @Test
    public void readingJsonLeavesBorrowedReaderOpen() throws Exception {
        // Reader 属于调用方，解析完成后仍由当前作用域关闭。
        TrackingReader reader = new TrackingReader("{\"message\":\"完成\"}");
        try (reader) {
            Map<String, String> value = JsonUtils.readValue(reader, FIELDS);
            assertEquals(Map.of("message", "完成"), value);
            assertFalse(reader.closed);
        }
        assertTrue(reader.closed);
    }

    @Test
    public void malformedJsonLeavesBorrowedReaderOpen() {
        // 解析失败也不能取得 Reader 的关闭权。
        TrackingReader reader = new TrackingReader("{\"message\":");
        try (reader) {
            assertThrows(JsonProcessingException.class, () -> JsonUtils.readValue(reader, FIELDS));
            assertFalse(reader.closed);
        }
        assertTrue(reader.closed);
    }

    @Test
    public void writingJsonLeavesBorrowedOutputStreamOpen() throws Exception {
        // 写出完整的 UTF-8 JSON 后，响应流仍由调用方关闭。
        TrackingOutputStream output = new TrackingOutputStream();
        try (output) {
            JsonUtils.writeValue(output, Map.of("message", "完成"));
            assertEquals("{\"message\":\"完成\"}", output.toString(StandardCharsets.UTF_8));
            assertFalse(output.closed);
        }
        assertTrue(output.closed);
    }

    @Test
    public void outputFailurePreservesIOExceptionAndLeavesBorrowedStreamOpen() throws Exception {
        // 输出流自身失败时，调用方收到原始异常，并保留资源关闭权。
        FailingOutputStream output = new FailingOutputStream();
        try (output) {
            IOException failure = assertThrows(IOException.class, () -> JsonUtils.writeValue(output, Map.of("id", 1)));
            assertSame(output.failure, failure);
            assertFalse(output.closed);
        }
        assertTrue(output.closed);
    }

    private static final class TrackingReader extends StringReader {
        private boolean closed;

        private TrackingReader(String json) {
            super(json);
        }

        @Override
        public void close() {
            this.closed = true;
            super.close();
        }
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        private final IOException failure = new IOException("test output failure");
        private       boolean     closed;

        @Override
        public void write(int value) throws IOException {
            throw this.failure;
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }
}
