/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.binding;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.domain.meta.*;

/** 发布时确定 Java 属性和 ProtoJSON 字段的对应表，编码时只转换已绑定名称。 */
final class GrpcValueMapping {
    static final  ObjectMapper                           MAPPER     = newMapper();
    private final JavaType                               type;
    private final Descriptor                             descriptor;
    private final Map<String, FieldDescriptor>           properties = new LinkedHashMap<>();
    private final Map<FieldDescriptor, String>           javaNames  = new LinkedHashMap<>();
    private final Map<FieldDescriptor, JavaType>         javaTypes  = new LinkedHashMap<>();
    private final Map<FieldDescriptor, GrpcValueMapping> children   = new LinkedHashMap<>();
    private final Map<String, String>                    enumWrite  = new LinkedHashMap<>();
    private final Map<String, String>                    enumRead   = new LinkedHashMap<>();
    private       EnumDescriptor                         enumDescriptor;

    private GrpcValueMapping(JavaType type, Descriptor descriptor) {
        this.type = type;
        this.descriptor = descriptor;
    }

    static GrpcValueMapping compile(JavaType type, Descriptor descriptor, Map<String, GrpcValueMapping> mappings) {
        String key = type.toCanonical() + "@" + descriptor.getFullName();
        GrpcValueMapping existing = mappings.get(key);
        if (existing != null) {
            return existing;
        }
        GrpcValueMapping result = new GrpcValueMapping(type, descriptor);
        mappings.put(key, result);
        Class<?> raw = type.getRawClass();
        boolean open = raw == Object.class || JsonNode.class.isAssignableFrom(raw) //
                || Map.class.isAssignableFrom(raw) || Message.class.isAssignableFrom(raw);
        boolean wellKnown = descriptor.getFullName().startsWith("google.protobuf.");
        if (open || wellKnown) {
            return result;
        }

        TypeMeta named = ServiceMetaParser.type(raw);
        String expectedName = descriptor.getName();
        boolean mismatchedTypeName = named.isDeclared() && !StringUtils.equals(named.getName(), expectedName);
        if (mismatchedTypeName && !StringUtils.equals(GrpcProtoCompiler.typeName(type), descriptor.getName())) {
            throw new IllegalArgumentException("Java type name differs from proto: " + raw.getName());
        }
        List<BeanPropertyDefinition> properties = properties(type);
        for (BeanPropertyDefinition property : properties) {
            PropertyMeta mapping = ServiceMetaParser.property(property);
            int id = mapping.getId();
            String declaredName = mapping.getDeclaredName();
            String name = property.getName();
            FieldDescriptor field = null;
            if (id != 0) {
                field = descriptor.findFieldByNumber(id);
                if (field == null) {
                    throw mismatch(type, name, "Unknown field id " + id);
                }
            }
            if (!StringUtils.isEmpty(declaredName)) {
                name = declaredName;
            }
            FieldDescriptor byName = descriptor.findFieldByName(name);
            if (byName == null) {
                for (FieldDescriptor candidate : descriptor.getFields()) {
                    if (StringUtils.equals(candidate.getJsonName(), name)) {
                        byName = candidate;
                        break;
                    }
                }
            }
            boolean explicitName = !StringUtils.isEmpty(declaredName);
            if (field != null && explicitName && field != byName) {
                throw mismatch(type, name, "Field name and id select different proto members");
            }
            if (field == null) {
                field = byName;
            }
            if (field == null) {
                throw mismatch(type, name, "No proto field for Java property");
            }
            if (result.javaNames.putIfAbsent(field, property.getName()) != null) {
                throw mismatch(type, name, "Several Java properties select one proto field");
            }
            result.properties.put(property.getName(), field);
            result.javaTypes.put(field, property.getPrimaryType());

            JavaType value = property.getPrimaryType();
            FieldDescriptor target = field;
            if (field.isMapField()) {
                if (!value.isMapLikeType()) {
                    throw mismatch(type, name, "Proto map requires a Java Map");
                }
                scalar(value.getKeyType(), field.getMessageType().findFieldByNumber(1));
                value = value.getContentType();
                target = field.getMessageType().findFieldByName("value");
            } else if (field.isRepeated()) {
                if (!value.isContainerType() || value.getRawClass() == byte[].class) {
                    throw mismatch(type, name, "Repeated proto field requires a Java collection or array");
                }
                value = value.getContentType();
            } else if (value.isContainerType() && value.getRawClass() != byte[].class) {
                throw mismatch(type, name, "Singular proto field cannot bind a Java container");
            }
            if (field.getRealContainingOneof() != null && value.isPrimitive()) {
                throw mismatch(type, name, "Oneof member must represent an unselected value");
            }
            if (target.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                result.children.put(field, compile(value, target.getMessageType(), mappings));
            } else if (target.getJavaType() == FieldDescriptor.JavaType.ENUM) {
                if (!value.isEnumType()) {
                    scalar(value, target);
                }
                result.children.put(field, enumeration(value, target.getEnumType()));
            } else {
                scalar(value, target);
            }
        }
        return result;
    }

    private static GrpcValueMapping enumeration(JavaType type, EnumDescriptor descriptor) {
        GrpcValueMapping result = new GrpcValueMapping(type, null);
        result.enumDescriptor = descriptor;
        if (!type.isEnumType()) {
            return result;
        }
        TypeMeta name = ServiceMetaParser.type(type.getRawClass());
        if (name.getDeclaredName() != null && !StringUtils.equals(name.getName(), descriptor.getName())) {
            throw mismatch(type, name.getName(), "Enum type name differs from proto");
        }
        for (Object constant : type.getRawClass().getEnumConstants()) {
            Enum<?> item = (Enum<?>) constant;
            EnumValueMeta annotation = ServiceMetaParser.enumValue(item);
            EnumValueDescriptor value;
            if (annotation.isDeclared()) {
                value = descriptor.findValueByNumber(annotation.getId());
                if (value != null && !StringUtils.isEmpty(annotation.getDeclaredName()) //
                        && !StringUtils.equals(annotation.getDeclaredName(), value.getName())) {
                    throw mismatch(type, item.name(), "Enum name and id differ from proto");
                }
            } else {
                value = descriptor.findValueByName(item.name());
            }
            if (value == null) {
                throw mismatch(type, item.name(), "Unknown proto enum value");
            }
            String javaName = MAPPER.valueToTree(item).asText();
            result.enumWrite.put(javaName, value.getName());
            result.enumRead.put(value.getName(), javaName);
            result.enumRead.put(Integer.toString(value.getNumber()), javaName);
        }
        return result;
    }

    private static void scalar(JavaType type, FieldDescriptor field) {
        Class<?> raw = type.getRawClass();
        if (raw == Object.class || JsonNode.class.isAssignableFrom(raw)) {
            return;
        }
        boolean numeric = Number.class.isAssignableFrom(raw) || raw.isPrimitive() && raw != boolean.class;
        boolean accepted = switch (field.getJavaType()) {
            case INT, LONG, FLOAT, DOUBLE -> numeric;
            case BOOLEAN -> raw == boolean.class || raw == Boolean.class;
            case STRING -> raw == String.class || raw == char.class || raw == Character.class;
            case BYTE_STRING -> raw == byte[].class;
            case ENUM -> numeric || raw == String.class;
            case MESSAGE -> false;
        };
        if (!accepted) {
            throw mismatch(type, field.getName(), "Java value cannot represent this proto field");
        }
    }

    JsonNode toProto(JsonNode value) {
        return this.convert(value, true);
    }

    JsonNode fromProto(JsonNode value) {
        return this.convert(value, false);
    }

    JavaType javaType() {
        return this.type;
    }

    private JsonNode convert(JsonNode value, boolean writing) {
        if (value == null || value.isNull()) {
            return value;
        }
        if (this.enumDescriptor != null && !this.type.isEnumType()) {
            if (!writing && !this.type.hasRawClass(String.class) && value.isTextual()) {
                EnumValueDescriptor item = this.enumDescriptor.findValueByName(value.asText());
                if (item == null) {
                    throw mismatch(this.type, value.asText(), "Unknown proto enum symbol");
                }
                return MAPPER.getNodeFactory().numberNode(item.getNumber());
            }
            return value;
        }
        if (!this.enumWrite.isEmpty()) {
            Map<String, String> names = writing ? this.enumWrite : this.enumRead;
            String name = names.get(value.asText());
            if (name == null) {
                throw mismatch(this.type, value.asText(), "Unknown mapped enum value");
            }
            return MAPPER.getNodeFactory().textNode(name);
        }
        if (this.properties.isEmpty() || !value.isObject()) {
            return value;
        }

        ObjectNode output = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            FieldDescriptor field = writing ? this.properties.get(entry.getKey()) //
                    : this.descriptor.findFieldByName(entry.getKey());
            if (field == null && !writing) {
                for (FieldDescriptor candidate : this.javaNames.keySet()) {
                    if (StringUtils.equals(candidate.getJsonName(), entry.getKey())) {
                        field = candidate;
                        break;
                    }
                }
            }
            String name;
            if (field == null) {
                name = entry.getKey();
            } else if (writing) {
                name = field.getJsonName();
            } else {
                name = this.javaNames.get(field);
            }
            if (name == null) {
                continue;
            }
            GrpcValueMapping child = this.children.get(field);
            JsonNode mapped = entry.getValue();
            if (child != null && !mapped.isNull()) {
                if (field.isMapField()) {
                    ObjectNode map = MAPPER.createObjectNode();
                    Iterator<Map.Entry<String, JsonNode>> items = mapped.fields();
                    while (items.hasNext()) {
                        Map.Entry<String, JsonNode> item = items.next();
                        map.set(item.getKey(), child.convert(item.getValue(), writing));
                    }
                    mapped = map;
                } else if (field.isRepeated()) {
                    ArrayNode array = MAPPER.createArrayNode();
                    for (JsonNode item : mapped) {
                        array.add(child.convert(item, writing));
                    }
                    mapped = array;
                } else {
                    mapped = child.convert(mapped, writing);
                }
            }
            output.set(name, mapped);
        }
        if (!writing) {
            this.defaults(output);
        }
        return output;
    }

    private void defaults(ObjectNode output) {
        for (Map.Entry<FieldDescriptor, String> entry : this.javaNames.entrySet()) {
            FieldDescriptor field = entry.getKey();
            String name = entry.getValue();
            if (output.has(name)) {
                continue;
            }
            if (field.isMapField()) {
                output.putObject(name);
            } else if (field.isRepeated()) {
                output.putArray(name);
            } else if (field.hasPresence() && !field.hasDefaultValue()) {
                if (!this.javaTypes.get(field).isPrimitive()) {
                    output.putNull(name);
                }
            } else {
                Object value = field.getDefaultValue();
                if (value instanceof EnumValueDescriptor enumeration) {
                    value = enumeration.getName();
                } else if (value instanceof ByteString bytes) {
                    value = bytes.toByteArray();
                }
                JsonNode node = MAPPER.valueToTree(value);
                GrpcValueMapping child = this.children.get(field);
                output.set(name, child == null ? node : child.fromProto(node));
            }
        }
    }

    static List<BeanPropertyDefinition> properties(JavaType type) {
        return MAPPER.getSerializationConfig().introspect(type).findProperties();
    }

    static JavaType messageType(Class<?> service, Method method, boolean request) {
        MethodMeta description = ServiceMetaParser.method(service, method);
        ResolvableType type = request ? description.getParameters().get(0).getMessageType() //
                : description.getResponseType();
        return javaType(type);
    }

    private static JavaType javaType(ResolvableType type) {
        Class<?> raw = type.resolve();
        if (raw == null || raw == Object.class) {
            throw new IllegalArgumentException("Protobuf requires a resolved Java type: " + type);
        }
        if (raw.isArray()) {
            return MAPPER.getTypeFactory().constructArrayType(javaType(type.getComponentType()));
        }
        ResolvableType[] arguments = type.getGenerics();
        if (arguments.length == 0) {
            return MAPPER.constructType(raw);
        }
        JavaType[] resolved = new JavaType[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            resolved[index] = javaType(arguments[index]);
        }
        return MAPPER.getTypeFactory().constructParametricType(raw, resolved);
    }

    static boolean streaming(Method method, boolean request) {
        return request ? method.getParameterTypes()[0] == Flow.Publisher.class //
                : method.getReturnType() == Flow.Publisher.class;
    }

    private static ObjectMapper newMapper() {
        ObjectMapper mapper = JsonMapper.builder().serializationInclusion(JsonInclude.Include.NON_NULL).build();
        AnnotationIntrospector common = new NopAnnotationIntrospector() {
            @Override
            public PropertyName findNameForSerialization(Annotated value) {
                return ServiceMetaParser.isProperty(value) ? PropertyName.USE_DEFAULT : null;
            }

            @Override
            public PropertyName findNameForDeserialization(Annotated value) {
                return this.findNameForSerialization(value);
            }
        };
        AnnotationIntrospector nativeAnnotations = mapper.getSerializationConfig().getAnnotationIntrospector();
        mapper.setAnnotationIntrospector(AnnotationIntrospector.pair(common, nativeAnnotations));
        return mapper;
    }

    private static IllegalArgumentException mismatch(JavaType type, String property, String message) {
        return new IllegalArgumentException(message + ": " + type + "." + property);
    }
}
