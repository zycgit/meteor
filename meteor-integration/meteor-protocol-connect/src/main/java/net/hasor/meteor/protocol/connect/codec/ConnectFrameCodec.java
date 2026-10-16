/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.connect.ConnectStatusException;

/** Connect 的五字节信封、有界 gzip 和 JSON 错误及结束消息编解码。 */
public final class ConnectFrameCodec {
    private static final ObjectMapper JSON = JsonMapper.builder() //
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION) //
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS) //
            .build();
    private final        int          maxMessageSize;

    public ConnectFrameCodec(int maxMessageSize) {
        if (maxMessageSize <= 0) {
            throw new IllegalArgumentException("maxMessageSize must be positive");
        }
        this.maxMessageSize = maxMessageSize;
    }

    public byte[] frame(byte[] message, String encoding) throws IOException {
        byte[] bytes = this.compress(message, encoding);
        int flags = identity(encoding) ? 0 : 1;
        return this.envelope(bytes, flags);
    }

    public byte[] end(ConnectStatusException failure, Map<String, List<String>> metadata) throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        Map<String, List<String>> trailing = new LinkedHashMap<>();
        if (failure != null) {
            document.put("error", this.errorDocument(failure));
            this.appendMetadata(trailing, failure.getMetadata());
        }
        this.appendMetadata(trailing, metadata);
        if (!trailing.isEmpty()) {
            document.put("metadata", trailing);
        }
        return this.envelope(JSON.writeValueAsBytes(document), 2);
    }

    public ConnectEnvelope read(InputStream input, String encoding) throws IOException {
        int flags = input.read();
        if (flags == -1) {
            return null;
        }
        if ((flags & ~3) != 0) {
            throw new IOException("Reserved Connect envelope flags are not supported");
        }

        // 在分配消息空间之前检查无符号长度，截断前缀和截断消息分别报告。
        byte[] size = input.readNBytes(4);
        if (size.length != 4) {
            throw new IOException("Truncated Connect envelope prefix");
        }
        long length = Integer.toUnsignedLong(ByteBuffer.wrap(size).getInt());
        this.checkSize(length);
        byte[] bytes = input.readNBytes((int) length);
        if (bytes.length != length) {
            throw new IOException("Truncated Connect envelope message");
        }
        if ((flags & 1) != 0) {
            if (identity(encoding)) {
                throw new IOException("Compressed Connect envelope requires a compression encoding");
            }
            if (bytes.length == 0) {
                throw new IOException("Compressed Connect envelope is empty");
            }
            bytes = this.decompress(bytes, encoding);
        }
        if ((flags & 2) != 0) {
            return this.readEnd(bytes);
        }
        return new ConnectEnvelope(bytes, null, Collections.emptyMap(), false);
    }

    public byte[] compress(byte[] message, String encoding) throws IOException {
        this.checkSize(message.length);
        if (identity(encoding)) {
            return message;
        }
        requireGzip(encoding);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(message);
        }
        byte[] compressed = buffer.toByteArray();
        this.checkSize(compressed.length);
        return compressed;
    }

    public byte[] decompress(byte[] message, String encoding) throws IOException {
        this.checkSize(message.length);
        if (identity(encoding)) {
            return message;
        }
        requireGzip(encoding);
        if (message.length == 0) {
            return message;
        }

        // 每个消息使用独立压缩上下文，膨胀后的长度也受同一消息上限约束。
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(message))) {
            ByteArrayOutputStream expanded = new ByteArrayOutputStream();
            byte[] buffer = new byte[Math.min(8192, this.maxMessageSize)];
            int count;
            while ((count = gzip.read(buffer)) != -1) {
                this.checkSize((long) expanded.size() + count);
                expanded.write(buffer, 0, count);
            }
            return expanded.toByteArray();
        }
    }

    public byte[] error(ConnectStatusException failure) throws IOException {
        // Unary 错误元信息通过 HTTP 头传输，编码正文时先校验同一个错误对象的元信息。
        this.readMetadata(JSON.valueToTree(failure.getMetadata()));
        byte[] bytes = JSON.writeValueAsBytes(this.errorDocument(failure));
        this.checkSize(bytes.length);
        return bytes;
    }

    public ConnectStatusException readError(byte[] message, Map<String, List<String>> metadata) throws IOException {
        this.checkSize(message.length);
        return this.readErrorDocument(JSON.readTree(message), metadata);
    }

    private ConnectEnvelope readEnd(byte[] message) throws IOException {
        JsonNode document = JSON.readTree(message);
        if (document == null || !document.isObject()) {
            throw new IOException("Connect end-stream message must be a JSON object");
        }
        Map<String, List<String>> metadata = this.readMetadata(document.get("metadata"));
        ConnectStatusException failure = null;
        if (document.has("error")) {
            failure = this.readErrorDocument(document.get("error"), metadata);
        }
        return new ConnectEnvelope(null, failure, metadata, true);
    }

    private ConnectStatusException readErrorDocument(JsonNode document, //
            Map<String, List<String>> metadata) throws IOException {
        if (document == null || !document.isObject()) {
            throw new IOException("Connect error must be a JSON object");
        }
        JsonNode code = document.get("code");
        if (code == null || !code.isTextual()) {
            throw new IOException("Connect error must contain a valid code");
        }
        JsonNode message = document.get("message");
        if (message != null && !message.isTextual()) {
            throw new IOException("Connect error message must be a string");
        }
        List<Map<String, Object>> details = this.readDetails(document.get("details"));
        try {
            String description = message == null ? "" : message.textValue();
            return new ConnectStatusException(code.textValue(), description, details, metadata);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Connect error contains an invalid code", failure);
        }
    }

    private List<Map<String, Object>> readDetails(JsonNode details) throws IOException {
        if (details == null) {
            return Collections.emptyList();
        }
        if (!details.isArray()) {
            throw new IOException("Connect error details must be an array");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode detail : details) {
            JsonNode type = detail.get("type");
            JsonNode value = detail.get("value");
            boolean validType = type != null && type.isTextual() && !type.textValue().isEmpty();
            boolean validValue = value != null && value.isTextual();
            if (!detail.isObject() || !validType || !validValue) {
                throw new IOException("Connect error details require type and value strings");
            }
            this.validateBase64(value.textValue());
            Map<String, Object> decoded = JSON.convertValue(detail, new TypeReference<Map<String, Object>>() {
            });
            result.add(decoded);
        }
        return result;
    }

    private Map<String, List<String>> readMetadata(JsonNode metadata) throws IOException {
        if (metadata == null) {
            return Collections.emptyMap();
        }
        if (!metadata.isObject()) {
            throw new IOException("Connect trailing metadata must be a JSON object");
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = metadata.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey().toLowerCase(Locale.ROOT);
            if (!name.matches("[0-9a-z_.-]+") || name.startsWith("connect-")) {
                throw new IOException("Invalid Connect trailing metadata name");
            }
            if (!field.getValue().isArray()) {
                throw new IOException("Connect trailing metadata values must be arrays");
            }
            List<String> values = result.computeIfAbsent(name, ignored -> new ArrayList<>());
            for (JsonNode value : field.getValue()) {
                if (!value.isTextual()) {
                    throw new IOException("Connect trailing metadata values must be strings");
                }
                String text = value.textValue();
                if (name.endsWith("-bin")) {
                    this.validateBase64(text);
                } else {
                    this.validateAscii(text);
                }
                values.add(text);
            }
        }
        return result;
    }

    private void validateBase64(String value) throws IOException {
        try {
            Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid Connect binary metadata or error detail", failure);
        }
    }

    private void validateAscii(String value) throws IOException {
        if (value.isEmpty()) {
            throw new IOException("Connect ASCII metadata must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                throw new IOException("Connect ASCII metadata must contain printable ASCII");
            }
        }
    }

    private Map<String, Object> errorDocument(ConnectStatusException failure) throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("code", failure.getCode());
        if (!failure.getDescription().isEmpty()) {
            document.put("message", failure.getDescription());
        }
        if (!failure.getDetails().isEmpty()) {
            List<Map<String, Object>> details = this.readDetails(JSON.valueToTree(failure.getDetails()));
            for (Map<String, Object> detail : details) {
                String encoded = (String) detail.get("value");
                byte[] bytes = Base64.getDecoder().decode(encoded);
                detail.put("value", Base64.getEncoder().withoutPadding().encodeToString(bytes));
            }
            document.put("details", details);
        }
        return document;
    }

    private void appendMetadata(Map<String, List<String>> target, //
            Map<String, List<String>> metadata) throws IOException {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        Map<String, List<String>> validated = this.readMetadata(JSON.valueToTree(metadata));
        for (Map.Entry<String, List<String>> entry : validated.entrySet()) {
            String name = entry.getKey();
            List<String> values = target.computeIfAbsent(name, ignored -> new ArrayList<>());
            for (String value : entry.getValue()) {
                if (name.endsWith("-bin")) {
                    byte[] bytes = Base64.getDecoder().decode(value);
                    values.add(Base64.getEncoder().withoutPadding().encodeToString(bytes));
                } else {
                    values.add(value);
                }
            }
        }
    }

    private byte[] envelope(byte[] message, int flags) {
        this.checkSize(message.length);
        return ByteBuffer.allocate(5 + message.length).put((byte) flags).putInt(message.length).put(message).array();
    }

    private void checkSize(long length) {
        if (length > this.maxMessageSize) {
            String message = "Connect message exceeds maxMessageSize";
            throw new ConnectStatusException("resource_exhausted", message, null, null);
        }
    }

    private static boolean identity(String encoding) {
        return encoding == null || encoding.isEmpty() || StringUtils.equalsIgnoreCase("identity", encoding);
    }

    private static void requireGzip(String encoding) {
        if (!StringUtils.equalsIgnoreCase("gzip", encoding)) {
            String message = "Supported Connect compression: identity, gzip";
            throw new ConnectStatusException("unimplemented", message, null, null);
        }
    }
}
