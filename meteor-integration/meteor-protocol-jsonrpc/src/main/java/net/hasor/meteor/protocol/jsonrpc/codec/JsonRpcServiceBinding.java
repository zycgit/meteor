/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc.codec;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;

/** 发布时确定线上名称、命名参数和继承泛型，运行时只查询这些对应表。 */
public final class JsonRpcServiceBinding {
    final         Map<String, List<Method>>     methods;
    private final Map<Method, String>           names      = new HashMap<>();
    private final Map<Method, String[]>         parameters = new HashMap<>();
    private final Map<Method, ResolvableType[]> inputs     = new HashMap<>();
    private final Map<Method, ResolvableType>   outputs    = new HashMap<>();

    private JsonRpcServiceBinding(Class<?> service, Map<String, List<Method>> methods) {
        this.methods = methods;
        for (Map.Entry<String, List<Method>> entry : methods.entrySet()) {
            for (Method method : entry.getValue()) {
                MethodMeta methodMeta = ServiceMetaParser.method(service, method);
                this.names.put(method, entry.getKey());
                ResolvableType[] types = new ResolvableType[method.getParameterCount()];
                String[] names = new String[method.getParameterCount()];
                for (int index = 0; index < names.length; index++) {
                    names[index] = methodMeta.getParameters().get(index).getName();
                    types[index] = methodMeta.getParameters().get(index).getType();
                }
                this.parameters.put(method, names);
                this.inputs.put(method, types);
                this.outputs.put(method, methodMeta.getReturnType());
            }
        }
    }

    public static JsonRpcServiceBinding prepare(MetBindInfo<?> service) {
        String alias = service.getAliasName("jsonrpc");
        if (StringUtils.isBlank(alias)) {
            alias = service.getBindName();
        }
        boolean reservedAlias = StringUtils.equals(alias, "rpc") || StringUtils.startsWith(alias, "rpc.");
        if (StringUtils.isBlank(alias) || reservedAlias) {
            return null;
        }
        // 与入站定位保持一致：方法名和参数数量必须能唯一确定一个入口。
        Set<String> signatures = new HashSet<>();
        Map<String, List<Method>> methods = new LinkedHashMap<>();
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            String name = MetRuntimeUtils.getProtocolMethodName(method);
            String signature = name + "/" + method.getParameterCount();
            if (!signatures.add(signature) || Flow.Publisher.class.isAssignableFrom(method.getReturnType())) {
                return null;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                if (Flow.Publisher.class.isAssignableFrom(parameter)) {
                    return null;
                }
            }
            Set<String> parameterNames = new HashSet<>();
            for (Parameter parameter : method.getParameters()) {
                String parameterName = MetRuntimeUtils.getProtocolParameterName(parameter);
                if (parameterName != null && !parameterNames.add(parameterName)) {
                    throw new IllegalArgumentException("Duplicate JSON-RPC parameter name: " + parameterName);
                }
            }
            methods.computeIfAbsent(name, ignored -> new ArrayList<>()).add(method);
        }
        methods.replaceAll((name, overloads) -> List.copyOf(overloads));
        return new JsonRpcServiceBinding(service.getBindType(), methods);
    }

    String name(Method method) {
        return this.names.get(method);
    }

    /** 导出已经确定的命名参数和操作名；Java 位置独立于 JSON 名称。 */
    public ObjectNode describeContract(ObjectNode description) {
        ArrayNode methods = description.putArray("methods");
        for (Map.Entry<Method, String> entry : this.names.entrySet()) {
            Method method = entry.getKey();
            ObjectNode operation = methods.addObject();
            operation.put("name", entry.getValue());
            operation.put("javaSignature", ServiceMetaParser.method(method).getJavaSignature());
            ArrayNode parameters = operation.putArray("parameters");
            for (int index = 0; index < method.getParameterCount(); index++) {
                ObjectNode parameter = parameters.addObject();
                parameter.put("name", this.parameterName(method, index));
                parameter.put("argIndex", index);
                parameter.put("type", this.parameterType(method, index).toString());
            }
            operation.putObject("returns").put("type", this.returnType(method).toString());
        }
        return description;
    }

    String parameterName(Method method, int index) {
        return this.parameters.get(method)[index];
    }

    ResolvableType parameterType(Method method, int index) {
        return this.inputs.get(method)[index];
    }

    ResolvableType returnType(Method method) {
        return this.outputs.get(method);
    }
}
