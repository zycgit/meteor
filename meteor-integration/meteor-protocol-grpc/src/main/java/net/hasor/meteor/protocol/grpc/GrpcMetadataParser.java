/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.meteor.MetOptionParser;

/** gRPC 多值元数据的 JSON 选项格式。 */
final class GrpcMetadataParser implements MetOptionParser<Map<String, List<String>>> {
    static final         GrpcMetadataParser                       INSTANCE = new GrpcMetadataParser();
    private static final ObjectMapper                             MAPPER   = new ObjectMapper();
    private static final TypeReference<Map<String, List<String>>> FIELDS   = new TypeReference<>() {
    };

    @Override
    public Map<String, List<String>> parse(String value) {
        if (value == null) {
            return Map.of();
        }

        try {
            Map<String, List<String>> fields = MAPPER.readValue(value, FIELDS);
            if (fields == null) {
                throw new IllegalArgumentException("gRPC metadata must be a JSON object");
            }

            Map<String, List<String>> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> field : fields.entrySet()) {
                snapshot.put(field.getKey(), List.copyOf(field.getValue()));
            }

            return Collections.unmodifiableMap(snapshot);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid gRPC metadata option", failure);
        }
    }

    @Override
    public String format(Map<String, List<String>> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Cannot encode gRPC metadata option", failure);
        }
    }
}
