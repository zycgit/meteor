/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.util.*;
import net.hasor.meteor.MetOptionSet;
import net.hasor.meteor.domain.OptionInfo;

/** 单次 gRPC 的元数据视图，以 JSON 选项保存多值字段并转换二进制值。 */
public final class GrpcMetadata {
    private static final String       HEADERS  = "grpc-metadata-headers";
    private static final String       TRAILERS = "grpc-metadata-trailers";
    private final        MetOptionSet options;

    public GrpcMetadata() {
        this(new OptionInfo());
    }

    private GrpcMetadata(MetOptionSet options) {
        this.options = options;
    }

    /** 取得请求或响应的元数据视图，修改直接写回该消息的选项。 */
    public static GrpcMetadata from(MetOptionSet message) {
        return new GrpcMetadata(message);
    }

    /** 读取头部快照，二进制字段的值为线上 Base64 文本。 */
    public Map<String, List<String>> getHeaders() {
        return this.options.getOption(HEADERS, GrpcMetadataParser.INSTANCE);
    }

    /** 读取尾部快照；流式响应的尾部在消息流结束时才可用。 */
    public Map<String, List<String>> getTrailers() {
        return this.options.getOption(TRAILERS, GrpcMetadataParser.INSTANCE);
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
        this.options.addOption(HEADERS, fields, GrpcMetadataParser.INSTANCE);
    }

    public void addTrailer(String name, String value) {
        Map<String, List<String>> fields = append(this.getTrailers(), asciiName(name), List.of(ascii(value)));
        this.options.addOption(TRAILERS, fields, GrpcMetadataParser.INSTANCE);
    }

    public void addBinaryHeader(String name, byte[] value) {
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(value);
        Map<String, List<String>> fields = append(this.getHeaders(), binaryName(name), List.of(encoded));
        this.options.addOption(HEADERS, fields, GrpcMetadataParser.INSTANCE);
    }

    public void addBinaryTrailer(String name, byte[] value) {
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(value);
        Map<String, List<String>> fields = append(this.getTrailers(), binaryName(name), List.of(encoded));
        this.options.addOption(TRAILERS, fields, GrpcMetadataParser.INSTANCE);
    }

    void receive(Map<String, List<String>> fields, boolean trailing) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            String name = field.getKey().toLowerCase(Locale.ROOT);
            if (!GrpcCodec.isMetadata(name)) {
                continue;
            }

            List<String> parts = new ArrayList<>();
            for (String value : field.getValue()) {
                if (name.endsWith("-bin")) {
                    // HTTP 允许合并同名二进制字段，先拆分再解码；接受有或无 padding。
                    for (String part : value.split(",", -1)) {
                        byte[] bytes = Base64.getDecoder().decode(part.trim());
                        parts.add(Base64.getEncoder().withoutPadding().encodeToString(bytes));
                    }
                } else {
                    parts.add(value);
                }
            }
            values.put(name, List.copyOf(parts));
        }
        String key = trailing ? TRAILERS : HEADERS;
        this.options.addOption(key, values, GrpcMetadataParser.INSTANCE);
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
        if (!GrpcCodec.isMetadata(key)) {
            throw new IllegalArgumentException("Reserved or invalid gRPC metadata key: " + name);
        }
        return key;
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
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126) {
                throw new IllegalArgumentException("gRPC text metadata must contain printable ASCII");
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
}
