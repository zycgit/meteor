/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.utils;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.serialize.coder.JsonSerializeCoder;
import net.hasor.meteor.serialize.coder.JsonSerializeDelegate;

/** 控制台内部共用的 JSON 读写；传入的流和 Reader 仍由调用方关闭。 */
public final class JsonUtils implements JsonSerializeDelegate {
    private static final ObjectMapper MAPPER   = JsonMapper.builder() //
            .disable(JsonParser.Feature.AUTO_CLOSE_SOURCE) //
            .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET).build();
    private static final JsonUtils    INSTANCE = new JsonUtils();

    private JsonUtils() {
    }

    /** 管理 RPC 复用统一 JSON 读写，保留嵌套 null 并兼容 SDK DTO。 */
    public static void configureRpc(MetContext context) {
        if (!(context.getSerializeCoder("Json") instanceof JsonSerializeCoder coder)) {
            throw new IllegalStateException("Console management requires the Json serializer");
        }
        coder.setDelegate(INSTANCE);
    }

    @Override
    public byte[] encode(Object value) throws IOException {
        return writeValueAsBytes(value);
    }

    @Override
    public Object decode(byte[] json, Class<?> type) throws IOException {
        return readCompatibleValue(json, type);
    }

    /** RPC 读取 SDK DTO 时忽略只读派生属性，普通配置和持久化读写仍保持原有严格语义。 */
    public static <T> T readCompatibleValue(byte[] json, Class<T> type) throws IOException {
        return MAPPER.readerFor(type).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);
    }

    /** 恢复 RPC 返回值的泛型，同时忽略 SDK 的只读派生属性，不改变共享 Mapper 配置。 */
    public static <T> T convertCompatibleValue(Object value, TypeReference<T> type) {
        try {
            JsonNode tree = MAPPER.valueToTree(value);
            return MAPPER.readerFor(type).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(tree);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot convert RPC value", failure);
        }
    }

    public static String writeValueAsString(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }

    public static byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsBytes(value);
    }

    public static void writeValue(OutputStream output, Object value) throws IOException {
        MAPPER.writeValue(output, value);
    }

    public static void writePrettyValue(File file, Object value) throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, value);
    }

    public static <T> T readValue(File file, TypeReference<T> type) throws IOException {
        return MAPPER.readValue(file, type);
    }

    public static <T> T readValue(byte[] json, TypeReference<T> type) throws IOException {
        return MAPPER.readValue(json, type);
    }

    public static <T> T readValue(Reader json, TypeReference<T> type) throws IOException {
        return MAPPER.readValue(json, type);
    }

    public static JsonNode readTree(String json) throws JsonProcessingException {
        return MAPPER.readTree(json);
    }

    public static JsonNode readTree(byte[] json) throws IOException {
        return MAPPER.readTree(json);
    }

    public static ObjectNode createObjectNode() {
        return MAPPER.createObjectNode();
    }

    public static <T> T treeToValue(JsonNode value, Class<T> type) throws JsonProcessingException {
        return MAPPER.treeToValue(value, type);
    }

    public static JsonNode valueToTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static <T> T convertValue(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    public static <T> T convertValue(Object value, TypeReference<T> type) {
        return MAPPER.convertValue(value, type);
    }
}
