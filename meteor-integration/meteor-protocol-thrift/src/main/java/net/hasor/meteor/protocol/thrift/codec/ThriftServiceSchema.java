/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.ParameterMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.protocol.thrift.definition.ThriftIdlType;
import org.apache.thrift.ProcessFunction;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.TFieldRequirementType;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.protocol.TType;

/** 插件内部发布定义；注解和生成契约均在发布时收敛到相同的方法及值类型。 */
public final class ThriftServiceSchema {
    private final String                           name;
    private       Map<String, String>              sources;
    private final Map<String, ThriftMethodBinding> javaMethods = new LinkedHashMap<>();
    private final Map<String, ThriftMethodBinding> wireMethods = new LinkedHashMap<>();

    ThriftServiceSchema(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    /** 导出统一编解码定义，编号取自实际 IDL 或注解编译结果。 */
    public ObjectNode describeContract(ObjectNode description) {
        description.put("serviceName", this.name);
        ArrayNode methods = description.putArray("methods");
        ObjectNode types = description.putObject("types");
        Map<ThriftType, String> visited = new IdentityHashMap<>();
        for (ThriftMethodBinding method : this.wireMethods.values()) {
            ObjectNode operation = methods.addObject();
            operation.put("javaSignature", ServiceMetaParser.method(method.method).getJavaSignature());
            operation.put("name", method.name);
            operation.put("message", method.oneway);
            ArrayNode parameters = operation.putArray("parameters");
            for (int index = 0; index < method.args.fields.size(); index++) {
                ThriftFieldBinding field = method.args.fields.get(index);
                ObjectNode argument = parameters.addObject();
                argument.put("argIndex", field.position < 0 ? index : field.position);
                describeField(field, argument, method.name + "." + field.field.name, types, visited);
            }
            if (method.result != null) {
                operation.put("responseType", describeType(method.result, method.name + ".result", types, visited));
            }
        }
        return description;
    }

    private static String describeType(ThriftType type, String reference, ObjectNode types, //
            Map<ThriftType, String> visited) {
        String previous = visited.get(type);
        if (previous != null) {
            return previous;
        }
        visited.put(type, reference);
        ObjectNode value = types.putObject(reference);
        value.put("name", type.name);
        value.put("wireType", type.wireType);
        value.put("binary", type.binary);
        value.put("union", type.union);
        if (type.key != null) {
            value.put("keyType", describeType(type.key, reference + ".key", types, visited));
        }
        if (type.element != null) {
            value.put("elementType", describeType(type.element, reference + ".element", types, visited));
        }
        ArrayNode fields = value.putArray("fields");
        for (ThriftFieldBinding field : type.fields) {
            describeField(field, fields.addObject(), reference + "." + field.field.name, types, visited);
        }
        ArrayNode constants = value.putArray("values");
        for (Map.Entry<String, Object> constant : type.enumNames.entrySet()) {
            ObjectNode entry = constants.addObject();
            entry.put("name", constant.getKey());
            entry.put("fieldId", type.enumNumbers.get(constant.getValue()));
        }
        return reference;
    }

    private static void describeField(ThriftFieldBinding field, ObjectNode member, String reference, //
            ObjectNode types, Map<ThriftType, String> visited) {
        member.put("name", field.field.name);
        member.put("fieldId", field.field.id);
        String requirement = switch (field.requirement) {
            case TFieldRequirementType.REQUIRED -> "required";
            case TFieldRequirementType.OPTIONAL -> "optional";
            default -> "default";
        };
        member.put("requirement", requirement);
        member.put("type", describeType(field.type, reference, types, visited));
    }

    /** 返回导入的资源图，或由当前已编译定义生成的 IDL。 */
    public Map<String, String> getSources() {
        if (this.sources != null) {
            return this.sources;
        }
        return new ThriftContractWriter().sources(this.name, this.wireMethods.values());
    }

    /** 文件入口与注解、生成契约入口返回同一种已准备结果。 */
    public static ThriftServiceSchema idl(MetBindInfo<?> service, ThriftIdlType definition) {
        ThriftServiceSchema schema = new ThriftIdlBinding(definition).bind(service);
        schema.sources = definition.document.getSources();
        return schema;
    }

    public static ThriftServiceSchema annotated(MetBindInfo<?> service) {
        Class<?> contract = service.getBindType();
        ThriftServiceSchema schema = new ThriftServiceSchema(contract.getSimpleName());
        ThriftSchemaBuilder types = new ThriftSchemaBuilder();
        for (Method method : methods(contract)) {
            MethodMeta methodMeta = ServiceMetaParser.method(contract, method);
            String name = methodMeta.getName();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid Thrift method name: " + name);
            }

            // 参数数组按 Java 位置逐项建立；field id 只用于匹配报文字段。
            ThriftType args = new ThriftType(TType.STRUCT, Object[].class);
            for (int index = 0; index < method.getParameterCount(); index++) {
                ParameterMeta field = methodMeta.getParameters().get(index);
                ResolvableType type = field.getType();
                String fallback = method.getParameters()[index].getName();
                int id = field.getId();
                String parameterName = field.getDeclaredName();
                ThriftFieldBinding parameter = types.annotatedField(id, parameterName, fallback, type, false);
                parameter.position = index;
                args.add(parameter);
            }
            ThriftType result = new ThriftType(TType.STRUCT, Object[].class);
            if (method.getReturnType() != void.class) {
                ThriftType type = types.javaType(methodMeta.getReturnType());
                addResult(result, (short) 0, "success", type);
            }
            for (Class<?> failure : method.getExceptionTypes()) {
                boolean checked = !RuntimeException.class.isAssignableFrom(failure);
                if (checked && failure != org.apache.thrift.TException.class) {
                    throw new IllegalArgumentException("Declared Thrift exceptions require an IDL contract: " + method);
                }
            }
            boolean message = service.isMessage() || methodMeta.isMessage();
            if (message && (method.getReturnType() != void.class || !result.fields.isEmpty())) {
                String messageError = "Thrift oneway requires void without declared exceptions: " + method;
                throw new IllegalArgumentException(messageError);
            }
            schema.add(new ThriftMethodBinding(method, name, message, args, message ? null : result));
        }
        if (schema.javaMethods.isEmpty()) {
            throw new IllegalArgumentException("Thrift service has no RPC methods");
        }
        return schema;
    }

    public static ThriftServiceSchema generated(MetBindInfo<?> service, String name, //
            Map<String, Method> methods, Map<String, ProcessFunction<?, ?, ?>> functions) {
        ThriftServiceSchema schema = new ThriftServiceSchema(name);
        ThriftSchemaBuilder types = new ThriftSchemaBuilder();
        for (Method method : methods(service.getBindType())) {
            String remoteName = MetRuntimeUtils.getProtocolMethodName(method);
            Method nativeMethod = methods.get(remoteName);
            ProcessFunction<?, ?, ?> function = functions.get(remoteName);
            if (nativeMethod == null || function == null || !compatible(method, nativeMethod)) {
                throw new IllegalArgumentException("Java method differs from generated Thrift contract: " + method);
            }
            boolean message = service.isMessage() || ServiceMetaParser.method(method).isMessage();
            if (message != function.isOneway()) {
                throw new IllegalArgumentException("Meteor message mode differs from IDL oneway: " + method);
            }
            Class<?> argsClass = function.getEmptyArgsInstance().getClass();
            ThriftType args = types.javaType(ResolvableType.forClass(argsClass));
            if (args.fields.size() != method.getParameterCount()) {
                throw new IllegalArgumentException("Generated Thrift args differ from method signature");
            }

            // 结果统一使用带 presence 的帧数组；成功编号固定为 0，其他编号是声明异常。
            ThriftType result = null;
            if (!message) {
                result = new ThriftType(TType.STRUCT, Object[].class);
                Class<?> resultClass = function.getEmptyResultInstance().getClass();
                for (Map.Entry<TFieldIdEnum, FieldMetaData> field : types.generatedFields(resultClass)) {
                    FieldMetaData metadata = field.getValue();
                    ThriftType type = types.generatedType(metadata.valueMetaData);
                    addResult(result, field.getKey().getThriftFieldId(), metadata.fieldName, type);
                }
            }
            schema.add(new ThriftMethodBinding(method, remoteName, message, args, result));
        }
        return schema;
    }

    ThriftMethodBinding javaMethod(String name) throws TApplicationException {
        return require(this.javaMethods, name);
    }

    ThriftMethodBinding wireMethod(String name) throws TApplicationException {
        return require(this.wireMethods, name);
    }

    void add(ThriftMethodBinding method) {
        if (this.javaMethods.putIfAbsent(method.method.getName(), method) != null) {
            throw new IllegalArgumentException("Thrift does not support Java method overloads");
        }
        if (this.wireMethods.putIfAbsent(method.name, method) != null) {
            throw new IllegalArgumentException("Duplicate Thrift wire method name: " + method.name);
        }
    }

    private static List<Method> methods(Class<?> contract) {
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Method candidate : MetRuntimeUtils.getServiceMethods(contract)) {
            if (candidate.isBridge() || candidate.isSynthetic()) {
                continue;
            }
            Method method = MetRuntimeUtils.getServiceMethod(contract, //
                    candidate.getName(), candidate.getParameterTypes());
            Method existing = methods.putIfAbsent(method.getName(), method);
            if (existing != null && !Arrays.equals(existing.getParameterTypes(), method.getParameterTypes())) {
                throw new IllegalArgumentException("Thrift does not support Java method overloads");
            }
        }
        return new ArrayList<>(methods.values());
    }

    private static void addResult(ThriftType result, short id, String name, ThriftType type) {
        ThriftFieldBinding field = new ThriftFieldBinding(id, name, TFieldRequirementType.OPTIONAL, type);
        field.position = result.fields.size();
        result.add(field);
    }

    private static boolean declares(Method method, Class<?> exception) {
        for (Class<?> declared : method.getExceptionTypes()) {
            if (declared.isAssignableFrom(exception)) {
                return true;
            }
        }
        return false;
    }

    private static boolean compatible(Method method, Method generated) {
        Type[] local = method.getGenericParameterTypes();
        Type[] nativeTypes = generated.getGenericParameterTypes();
        boolean returnMatches = compatible(method.getGenericReturnType(), generated.getGenericReturnType());
        if (local.length != nativeTypes.length || !returnMatches) {
            return false;
        }
        for (int index = 0; index < local.length; index++) {
            if (!compatible(local[index], nativeTypes[index])) {
                return false;
            }
        }
        return true;
    }

    private static boolean compatible(Type local, Type generated) {
        if (local.equals(generated)) {
            return true;
        }
        boolean localBytes = local == byte[].class && generated == ByteBuffer.class;
        boolean generatedBytes = local == ByteBuffer.class && generated == byte[].class;
        if (localBytes || generatedBytes) {
            return true;
        }
        if (local instanceof ParameterizedType left && generated instanceof ParameterizedType right) {
            return left.getRawType().equals(right.getRawType()) && //
                    Arrays.equals(left.getActualTypeArguments(), right.getActualTypeArguments());
        }
        return false;
    }

    private static ThriftMethodBinding require(Map<String, ThriftMethodBinding> methods, String name) //
            throws TApplicationException {
        ThriftMethodBinding method = methods.get(name);
        if (method == null) {
            throw new TApplicationException(TApplicationException.UNKNOWN_METHOD, "Unknown Thrift method: " + name);
        }
        return method;
    }
}
