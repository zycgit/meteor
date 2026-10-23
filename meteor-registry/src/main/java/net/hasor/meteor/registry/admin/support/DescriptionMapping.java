/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.admin.support;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.registry.admin.description.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 将内部存储或管理 RPC 的描述转换为公开 DTO，不查询存储，也不改变原始正文。 */
final class DescriptionMapping {
    private DescriptionMapping() {
    }

    static MetServiceDescription service(ObjectNode source) {
        ObjectNode remaining = source.deepCopy();
        JsonNode methods = remaining.remove("methods");
        JsonNode types = remaining.remove("types");
        JsonNode protocols = remaining.remove("protocols");
        MetServiceDescription result = read(remaining, MetServiceDescription.class, //
                MetServiceDescription::setExtensions, "contractServiceId", "javaType", "message");

        // 公共方法保留声明顺序，类型图保留完整泛型引用，不尝试加载用户类。
        if (methods != null) {
            for (JsonNode method : methods) {
                result.getMethods().add(method((ObjectNode) method));
            }
        }
        if (types != null) {
            types.fields().forEachRemaining(entry -> {
                result.getTypes().put(entry.getKey(), type((ObjectNode) entry.getValue()));
            });
        }

        // 协议名和连接器名分别保留，不能把同名协议的不同端点折叠为一份。
        if (protocols != null) {
            protocols.fields().forEachRemaining(entry -> {
                Map<String, MetProtocolDescription> endpoints = new LinkedHashMap<>();
                entry.getValue().fields().forEachRemaining(endpoint -> {
                    endpoints.put(endpoint.getKey(), protocol((ObjectNode) endpoint.getValue()));
                });
                result.getProtocols().put(entry.getKey(), endpoints);
            });
        }
        return result;
    }

    private static MetMethodDescription method(ObjectNode source) {
        ObjectNode remaining = source.deepCopy();
        JsonNode parameters = remaining.remove("parameters");
        JsonNode returns = remaining.remove("returns");
        MetMethodDescription result = read(remaining, MetMethodDescription.class, //
                MetMethodDescription::setExtensions, "methodId", "name", "javaSignature", "javaName", //
                "exceptions", "message");
        if (parameters != null) {
            for (JsonNode parameter : parameters) {
                result.getParameters().add(field((ObjectNode) parameter));
            }
        }
        if (returns != null && !returns.isNull()) {
            result.setReturns(field((ObjectNode) returns));
        }
        return result;
    }

    private static MetTypeDescription type(ObjectNode source) {
        ObjectNode remaining = source.deepCopy();
        JsonNode properties = remaining.remove("properties");
        JsonNode values = remaining.remove("values");
        MetTypeDescription result = read(remaining, MetTypeDescription.class, //
                MetTypeDescription::setExtensions, "name", "javaType", "kind", "elementType", "arguments");
        if (properties != null) {
            for (JsonNode property : properties) {
                result.getProperties().add(field((ObjectNode) property));
            }
        }
        if (values != null) {
            for (JsonNode value : values) {
                result.getValues().add(field((ObjectNode) value));
            }
        }
        return result;
    }

    private static MetFieldDescription field(ObjectNode source) {
        return read(source.deepCopy(), MetFieldDescription.class, MetFieldDescription::setExtensions, //
                "fieldId", "name", "argIndex", "javaName", "type");
    }

    @SuppressWarnings("unchecked")
    private static MetProtocolDescription protocol(ObjectNode source) {
        // 方法、返回值和类型图属于协议自己的形状，例如 SOAP 返回列表及 ONC RPC 的数值版本。
        ObjectNode remaining = source.deepCopy();
        JsonNode methods = remaining.remove("methods");
        JsonNode types = remaining.remove("types");
        MetProtocolDescription result = read(remaining, MetProtocolDescription.class, //
                MetProtocolDescription::setExtensions, "name", "serializeType");
        if (methods != null) {
            result.setMethods((List<Map<String, Object>>) value(methods));
        }
        if (types != null) {
            result.setTypes((Map<String, Object>) value(types));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(ObjectNode remaining, Class<T> type, //
            BiConsumer<T, Map<String, Object>> extensions, String... fields) {
        ObjectNode selected = remaining.objectNode();
        for (String field : fields) {
            JsonNode value = remaining.remove(field);
            if (value != null) {
                selected.set(field, value);
            }
        }

        // 先隔开已知字段，未知字段即使叫 extensions 也只是原始扩展数据，不冒充 DTO 属性。
        T result = RegistryJson.copy(selected, type);
        Map<String, Object> extra = (Map<String, Object>) value(remaining);
        extensions.accept(result, extra);
        return result;
    }

    private static Object value(JsonNode source) {
        // 动态字段只转换数据形状，数值使用原始 Number，不能把高精度十进制缩窄为 double。
        if (source == null || source.isNull()) {
            return null;
        } else if (source.isObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.fields().forEachRemaining(entry -> result.put(entry.getKey(), value(entry.getValue())));
            return result;
        } else if (source.isArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonNode item : source) {
                result.add(value(item));
            }
            return result;
        } else if (source.isNumber()) {
            return source.numberValue();
        } else if (source.isBoolean()) {
            return source.booleanValue();
        } else if (source.isTextual() || source.isBinary()) {
            return source.asText();
        }
        throw new IllegalArgumentException("Description contains non-JSON data: " + source.getNodeType());
    }
}
