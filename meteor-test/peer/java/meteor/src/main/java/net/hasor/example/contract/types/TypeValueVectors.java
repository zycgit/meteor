/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.types;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.StringUtils;

/** JSON 只维护跨语言输入，每次恢复独立的真实 Java 类型对象供 RPC 使用。 */
public final class TypeValueVectors {
    private static final ObjectMapper JSON = new ObjectMapper();

    private TypeValueVectors() {
    }

    public static List<String> names() throws IOException {
        List<String> names = new ArrayList<>();
        table().required("vectors").fieldNames().forEachRemaining(names::add);
        return names;
    }

    public static List<String> names(String type) throws IOException {
        List<String> names = new ArrayList<>();
        var vectors = table().required("vectors").fields();
        while (vectors.hasNext()) {
            var vector = vectors.next();
            String vectorType = vector.getValue().required("type").textValue();
            if (StringUtils.equals(type, vectorType)) {
                names.add(vector.getKey());
            }
        }
        return names;
    }

    public static String type(String name) throws IOException {
        return table().required("vectors").required(name).required("type").textValue();
    }

    public static TypeValue value(String name) throws IOException {
        // 默认字段均使用可表示值，每个向量只替换其对应的一项业务字段。
        JsonNode table = table();
        ObjectNode value = table.required("defaults").deepCopy();
        JsonNode vector = table.required("vectors").required(name);
        String field = vector.required("field").textValue();
        value.set(field, vector.required("value"));
        value.put("caseId", name);
        value.put("receipt", "client:" + name);

        // JSON 的整数文本与 Base64 在这里恢复为 long、BigInteger 和 byte[]。
        return JSON.treeToValue(value, TypeValue.class);
    }

    private static JsonNode table() throws IOException {
        try (InputStream input = TypeValueVectors.class.getResourceAsStream("/data/types/values.json")) {
            if (input == null) {
                throw new IOException("Missing shared type vectors");
            }
            return JSON.readTree(input);
        }
    }
}
