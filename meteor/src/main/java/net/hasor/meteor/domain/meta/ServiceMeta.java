/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.meta;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.annotation.MetService;

/** Java 服务的公共声明；服务配置直接读取注解，最终注册值由 ServiceDomain 管理。 */
public final class ServiceMeta {
    private final Class<?>                                javaType;
    private final MetService                              declaration;
    private final List<MethodMeta>                        exposedMethods;
    private final boolean                                 message;
    private final Map<Method, MethodMeta>                 methods;
    private final ConcurrentMap<ResolvableType, TypeMeta> types = new ConcurrentHashMap<>();

    ServiceMeta(Class<?> javaType, MetService declaration, boolean message, Map<Method, MethodMeta> methods) {
        this.javaType = javaType;
        this.declaration = declaration;
        this.message = message;
        this.methods = Collections.unmodifiableMap(methods);
        List<MethodMeta> exposed = new ArrayList<>();
        for (MethodMeta method : methods.values()) {
            if (!method.isIgnored()) {
                exposed.add(method);
            }
        }
        this.exposedMethods = List.copyOf(exposed);
    }

    public Class<?> getJavaType() {
        return this.javaType;
    }

    public String getName() {
        return this.declaration == null ? "" : this.declaration.name();
    }

    public String getGroup() {
        return this.declaration == null ? "" : this.declaration.group();
    }

    public String getVersion() {
        return this.declaration == null ? "" : this.declaration.version();
    }

    public int getClientTimeout() {
        return this.declaration == null ? -1 : this.declaration.clientTimeout();
    }

    public String getSerializeType() {
        return this.declaration == null ? "" : this.declaration.serializeType();
    }

    public boolean isMessage() {
        return this.message;
    }

    public Collection<MethodMeta> getMethods() {
        return this.exposedMethods;
    }

    public MethodMeta getMethod(Method method) {
        return this.methods.get(method);
    }

    /** DTO 按完整声明类型复用描述，泛型实参不同的类型分别保存。 */
    public TypeMeta getType(ResolvableType type) {
        return this.types.computeIfAbsent(type, ServiceMetaParser::type);
    }

    /** 导出公共声明的纯数据描述；不包含反射对象、业务实例或协议配置。 */
    public ObjectNode createServiceDescription() {
        ObjectNode description = JsonNodeFactory.instance.objectNode();
        description.put("javaType", this.javaType.getName());
        description.put("message", this.message);
        ArrayNode methods = description.putArray("methods");
        ObjectNode types = description.putObject("types");

        // Java 参数列表决定调用位置；契约编号与名称分别保留。
        for (MethodMeta method : this.exposedMethods) {
            if (method.getJavaMethod().isBridge() || method.getJavaMethod().isSynthetic()) {
                continue;
            }
            ObjectNode entry = methods.addObject();
            entry.put("javaSignature", method.getJavaSignature());
            entry.put("javaName", method.getJavaName());
            entry.put("name", method.getName());
            entry.put("message", this.message || method.isMessage());
            ArrayNode parameters = entry.putArray("parameters");
            for (ParameterMeta parameter : method.getParameters()) {
                ObjectNode argument = parameters.addObject();
                argument.put("argIndex", parameter.getArgIndex());
                argument.put("name", parameter.getName());
                if (parameter.getId() != 0) {
                    argument.put("fieldId", parameter.getId());
                } else {
                    argument.putNull("fieldId");
                }
                argument.put("type", this.describeType(parameter.getType(), types));
            }

            ObjectNode returns = entry.putObject("returns");
            returns.put("name", method.getReturnName());
            returns.put("type", this.describeType(method.getReturnType(), types));
            ArrayNode exceptions = entry.putArray("exceptions");
            for (Class<?> exception : method.getExceptionTypes()) {
                exceptions.add(this.describeType(ResolvableType.forClass(exception), types));
            }
        }
        return description;
    }

    private String describeType(ResolvableType type, ObjectNode types) {
        String reference = type.toString();
        if (types.has(reference)) {
            return reference;
        }

        // 先登记引用再展开成员，普通递归 DTO 不会无限嵌套。
        TypeMeta meta = this.getType(type);
        Class<?> raw = meta.getRawType();
        ObjectNode description = types.putObject(reference);
        description.put("javaType", raw.getTypeName());
        description.put("name", meta.getName());
        String kind = typeKind(type, raw);
        description.put("kind", kind);
        if (type.isArray()) {
            description.put("elementType", this.describeType(type.getComponentType(), types));
            return reference;
        }

        ArrayNode arguments = description.putArray("arguments");
        for (ResolvableType argument : meta.getArguments()) {
            arguments.add(this.describeType(argument, types));
        }

        if (raw.isEnum()) {
            ArrayNode values = description.putArray("values");
            for (EnumValueMeta constant : meta.getEnumValues().values()) {
                ObjectNode value = values.addObject();
                value.put("javaName", constant.getJavaName());
                value.put("name", constant.getName());
                if (constant.isDeclared()) {
                    value.put("fieldId", constant.getId());
                } else {
                    value.putNull("fieldId");
                }
            }
        } else if (StringUtils.equals("object", kind)) {
            ArrayNode properties = description.putArray("properties");
            for (PropertyMeta property : meta.getProperties().values()) {
                ObjectNode member = properties.addObject();
                member.put("javaName", property.getJavaName());
                member.put("name", property.getName());
                if (property.getId() != 0) {
                    member.put("fieldId", property.getId());
                } else {
                    member.putNull("fieldId");
                }
                member.put("type", this.describeType(property.getType(), types));
            }
        }
        return reference;
    }

    private static String typeKind(ResolvableType type, Class<?> raw) {
        if (type.resolve() == null) {
            return "variable";
        } else if (type.isArray()) {
            return "array";
        } else if (raw.isEnum()) {
            return "enum";
        } else if (Map.class.isAssignableFrom(raw)) {
            return "map";
        } else if (Collection.class.isAssignableFrom(raw)) {
            return "collection";
        } else if (Flow.Publisher.class.isAssignableFrom(raw)) {
            return "publisher";
        } else if (raw == Void.TYPE || raw == Void.class) {
            return "void";
        } else if (raw == Object.class) {
            return "any";
        } else if (raw.isPrimitive() || raw.getName().startsWith("java.")) {
            return "scalar";
        } else {
            return "object";
        }
    }
}
