/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect;
import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.meteor.MetOptionSet;
import net.hasor.meteor.domain.OptionInfo;

/** 单次 Connect 的元数据视图，以 JSON 选项保存多值字段并转换二进制值。 */
public final class ConnectMetadata {
    private static final String       HEADERS      = "connect-metadata-headers";
    private static final String       TRAILERS     = "connect-metadata-trailers";
    private static final Set<String>  HTTP_HEADERS = Set.of(//
            "content-type", "content-length", "content-encoding", "accept-encoding", "host", "connection", //
            "transfer-encoding", "te", "user-agent");
    private static final ObjectMapper MAPPER       = new ObjectMapper();
    private static final JavaType     FIELDS       = fieldsType();
    private final        MetOptionSet options;

    private ConnectMetadata(MetOptionSet options) {
        this.options = options;
    }

    /** 创建一份不依附请求或响应的元数据。 */
    public static ConnectMetadata create() {
        return new ConnectMetadata(new OptionInfo());
    }

    /** 取得请求或响应的元数据视图，修改直接写回该消息的选项。 */
    public static ConnectMetadata from(MetOptionSet message) {
        return new ConnectMetadata(message);
    }

    /** 读取头部快照，二进制字段的值为线上 Base64 文本。 */
    public Map<String, List<String>> getHeaders() {
        return parse(this.options.getOption(HEADERS));
    }

    /** 读取尾部快照；流式响应的尾部在消息流结束时才可用。 */
    public Map<String, List<String>> getTrailers() {
        return parse(this.options.getOption(TRAILERS));
    }

    public List<String> getHeader(String name) {
        return this.getHeaders().getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    public List<String> getTrailer(String name) {
        return this.getTrailers().getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    public List<byte[]> getBinaryHeader(String name) {
        return binary(this.getHeader(binaryName(name)));
    }

    public List<byte[]> getBinaryTrailer(String name) {
        return binary(this.getTrailer(binaryName(name)));
    }

    public void addHeader(String name, String value) {
        Map<String, List<String>> fields = append(this.getHeaders(), asciiName(name), List.of(ascii(value)));
        this.options.addOption(HEADERS, format(fields));
    }

    public void addTrailer(String name, String value) {
        Map<String, List<String>> fields = append(this.getTrailers(), asciiName(name), List.of(ascii(value)));
        this.options.addOption(TRAILERS, format(fields));
    }

    public void addBinaryHeader(String name, byte[] value) {
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(value);
        Map<String, List<String>> fields = append(this.getHeaders(), binaryName(name), List.of(encoded));
        this.options.addOption(HEADERS, format(fields));
    }

    public void addBinaryTrailer(String name, byte[] value) {
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(value);
        Map<String, List<String>> fields = append(this.getTrailers(), binaryName(name), List.of(encoded));
        this.options.addOption(TRAILERS, format(fields));
    }

    private static Map<String, List<String>> append(Map<String, List<String>> source, String key, List<String> added) {
        Map<String, List<String>> copy = new LinkedHashMap<>(source);
        List<String> values = new ArrayList<>(copy.getOrDefault(key, List.of()));
        values.addAll(added);
        copy.put(key, List.copyOf(values));
        return Collections.unmodifiableMap(copy);
    }

    private static String name(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!isApplicationKey(key)) {
            throw new IllegalArgumentException("Reserved or invalid Connect metadata key: " + name);
        }
        return key;
    }

    private static boolean isApplicationKey(String name) {
        return name != null && name.matches("[0-9a-z_.-]+") && !name.startsWith("connect-") && //
                !HTTP_HEADERS.contains(name);
    }

    private static String asciiName(String name) {
        String key = name(name);
        if (key.endsWith("-bin")) {
            throw new IllegalArgumentException("Use the binary metadata API for " + name);
        }
        return key;
    }

    private static String binaryName(String name) {
        String key = name(name);
        if (!key.endsWith("-bin")) {
            throw new IllegalArgumentException("Binary metadata keys must end in -bin: " + name);
        }
        return key;
    }

    private static String ascii(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Connect text metadata must not be empty");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126) {
                throw new IllegalArgumentException("Connect text metadata must contain printable ASCII");
            }
        }
        return value;
    }

    private static List<byte[]> binary(List<String> values) {
        List<byte[]> decoded = new ArrayList<>();
        for (String value : values) {
            decoded.add(Base64.getDecoder().decode(value));
        }
        return List.copyOf(decoded);
    }

    private static JavaType fieldsType() {
        JavaType keyType = MAPPER.getTypeFactory().constructType(String.class);
        JavaType valueType = MAPPER.getTypeFactory().constructCollectionType(List.class, String.class);
        return MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, keyType, valueType);
    }

    private static Map<String, List<String>> parse(String value) {
        if (value == null) {
            return Map.of();
        }

        try {
            Map<String, List<String>> fields = MAPPER.readValue(value, FIELDS);
            if (fields == null) {
                throw new IllegalArgumentException("Connect metadata must be a JSON object");
            }

            Map<String, List<String>> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> field : fields.entrySet()) {
                snapshot.put(field.getKey(), List.copyOf(field.getValue()));
            }
            return Collections.unmodifiableMap(snapshot);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid Connect metadata option", failure);
        }
    }

    private static String format(Map<String, List<String>> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Cannot encode Connect metadata option", failure);
        }
    }
}
