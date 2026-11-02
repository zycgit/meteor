/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.binding;
import java.lang.reflect.Method;
import java.util.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.ParameterMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;
import net.hasor.meteor.protocol.oncrpc.definition.OncRpcIdlProcedure;
import net.hasor.meteor.protocol.oncrpc.definition.OncRpcIdlProgram;
import net.hasor.meteor.protocol.oncrpc.definition.XdrIdlMember;
import net.hasor.meteor.protocol.oncrpc.definition.XdrIdlType;

/** 一个端点上已完成 Java 类型绑定的程序，不改变借入的原始资源。 */
public final class OncRpcServiceBinding {
    private final Object                              resource;
    private final long                                program;
    private final long                                version;
    private final Map<String, List<XdrCodec<Object>>> parameters     = new LinkedHashMap<>();
    private final Map<String, XdrCodec<Object>>       results        = new LinkedHashMap<>();
    private final Map<String, Long>                   procedures     = new LinkedHashMap<>();
    private final Map<Long, Method>                   methods        = new LinkedHashMap<>();
    private final Map<String, Method>                 namedMethods   = new LinkedHashMap<>();
    private       String                              programName;
    private final List<OncRpcIdlProcedure>            definitions    = new ArrayList<>();
    private final Map<String, int[]>                  parameterOrder = new LinkedHashMap<>();

    OncRpcServiceBinding(Object resource, MetBindInfo<?> service, ProtocolConfig protocol) {
        this.resource = resource;
        int maxBytes = protocol.integer("maxMessageSize", 8 * 1024 * 1024);
        int maxElements = protocol.integer("maxContainerSize", 100000);
        if (resource instanceof OncRpcIdlResource file) {
            OncRpcIdlProgram program = file.program(service.getBindType());
            this.programName = program.name;
            this.program = program.id;
            this.version = file.version(program);
            this.prepareFile(file, program.versions.get(this.version), service, maxBytes, maxElements);
        } else {
            OncRpcServiceResource declaration = (OncRpcServiceResource) resource;
            this.programName = MetRuntimeUtils.getProtocolTypeName(service.getBindType());
            this.program = declaration.getProgram();
            this.version = declaration.getVersion();
            XdrSchemaBuilder types = new XdrSchemaBuilder(declaration, maxBytes, maxElements);
            for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                MethodMeta methodMeta = ServiceMetaParser.method(service.getBindType(), method);
                methodMeta.getName();
                List<XdrCodec<Object>> parameters = new ArrayList<>();
                for (int index = 0; index < method.getParameterCount(); index++) {
                    ResolvableType type = methodMeta.getParameters().get(index).getType();
                    parameters.add(require(types.codec(type), type));
                }
                ResolvableType result = methodMeta.getReturnType();
                this.parameters.put(method.getName(), List.copyOf(parameters));
                this.results.put(method.getName(), require(types.codec(result), result));
                for (Map.Entry<Long, Method> entry : declaration.getMethods().entrySet()) {
                    if (StringUtils.equals(entry.getValue().getName(), method.getName())) {
                        this.add(entry.getKey(), method);
                        this.define(method, entry.getKey(), service.getBindType(), types);
                    }
                }
            }
        }
    }

    /** 文件输入返回原始资源，注解输入从已编译声明导出。 */
    public Map<String, String> getSources() {
        if (this.resource instanceof OncRpcIdlResource file) {
            return file.getSources();
        }
        return new XdrContractWriter().sources(this.programName, this.program, this.version, this.definitions);
    }

    private void define(Method method, long id, Class<?> service, XdrSchemaBuilder types) {
        MethodMeta methodMeta = ServiceMetaParser.method(service, method);
        String name = methodMeta.getName();
        XdrIdlType input;
        if (method.getParameterCount() == 0) {
            input = new XdrIdlType("void", null);
        } else if (method.getParameterCount() == 1) {
            ResolvableType parameter = methodMeta.getParameters().get(0).getType();
            input = this.namedType(types.definition(parameter), name + "_Input");
        } else {
            input = new XdrIdlType("struct", name + "_Args");
            for (int index = 0; index < method.getParameterCount(); index++) {
                ResolvableType parameter = methodMeta.getParameters().get(index).getType();
                String member = MetRuntimeUtils.getProtocolParameterName(method.getParameters()[index]);
                if (member == null) {
                    member = "arg" + index;
                }
                XdrIdlType value = this.namedType(types.definition(parameter), name + "_Arg" + index);
                input.members.add(new XdrIdlMember(member, value));
            }
        }
        ResolvableType result = methodMeta.getReturnType();
        XdrIdlType output = this.namedType(types.definition(result), name + "_Result");
        this.definitions.add(new OncRpcIdlProcedure(name, id, input, output));
    }

    private XdrIdlType namedType(XdrIdlType type, String fallback) {
        if (type == null || type.name != null) {
            return type;
        }
        boolean unnamed = StringUtils.equals(type.kind, "array") || StringUtils.equals(type.kind, "string");
        if (!unnamed && !StringUtils.equals(type.kind, "opaque")) {
            return type;
        }
        XdrIdlType named = new XdrIdlType(type.kind, fallback);
        named.element = type.element;
        named.length = type.length;
        return named;
    }

    private void prepareFile(OncRpcIdlResource file, Map<String, OncRpcIdlProcedure> procedures, //
            MetBindInfo<?> service, int maxBytes, int maxElements) {
        XdrSchemaBuilder types = new XdrSchemaBuilder(null, maxBytes, maxElements);
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            MethodMeta methodMeta = ServiceMetaParser.method(service.getBindType(), method);
            OncRpcIdlProcedure procedure = file.procedure(procedures, method);
            this.definitions.add(procedure);
            XdrIdlType input = file.document.resolve(procedure.input);
            List<XdrCodec<Object>> parameters = new ArrayList<>();
            int count = method.getParameterCount();
            if (count == 0) {
                if (!StringUtils.equals(input.kind, "void")) {
                    throw new IllegalArgumentException("RPC input differs from zero Java parameters: " + method);
                }
            } else if (count == 1) {
                ResolvableType type = methodMeta.getParameters().get(0).getType();
                parameters.add(require(types.idlCodec(type, input, file.document), type));
            } else {
                boolean struct = StringUtils.equals(input.kind, "struct");
                if (!struct || input.members.size() != count) {
                    String message = "Multiple Java parameters require a matching XDR struct: " + method;
                    throw new IllegalArgumentException(message);
                }
                int[] order = new int[count];
                Set<Integer> used = new HashSet<>();
                for (int wireIndex = 0; wireIndex < count; wireIndex++) {
                    XdrIdlMember member = input.members.get(wireIndex);
                    int javaIndex = this.parameterIndex(method, member.name, wireIndex);
                    if (!used.add(javaIndex)) {
                        throw new IllegalArgumentException("Duplicate XDR input member binding: " + member.name);
                    }
                    order[wireIndex] = javaIndex;
                    ResolvableType type = methodMeta.getParameters().get(javaIndex).getType();
                    parameters.add(require(types.idlCodec(type, member.type, file.document), type));
                }
                this.parameterOrder.put(method.getName(), order);
            }
            ResolvableType result = methodMeta.getReturnType();
            XdrCodec<Object> resultCodec = types.idlCodec(result, procedure.result, file.document);
            this.results.put(method.getName(), require(resultCodec, result));
            this.parameters.put(method.getName(), List.copyOf(parameters));
            this.add(procedure.id, method);
        }
    }

    private int parameterIndex(Method method, String name, int fallback) {
        int selected = -1;
        for (int index = 0; index < method.getParameterCount(); index++) {
            String declared = MetRuntimeUtils.getProtocolParameterName(method.getParameters()[index]);
            if (StringUtils.equals(name, declared)) {
                if (selected >= 0) {
                    throw new IllegalArgumentException("Duplicate Java parameter name: " + name);
                }
                selected = index;
            }
        }
        if (selected >= 0) {
            return selected;
        }
        ParameterMeta mapping = ServiceMetaParser.parameter(method.getParameters()[fallback]);
        if (!mapping.getDeclaredName().isEmpty()) {
            String message = "Explicit Java parameter name has no XDR member: " + mapping.getDeclaredName();
            throw new IllegalArgumentException(message);
        }
        return fallback;
    }

    private void add(long id, Method method) {
        Method sameId = this.methods.putIfAbsent(id, method);
        Method sameName = this.namedMethods.putIfAbsent(method.getName(), method);
        if (sameId != null || sameName != null) {
            throw new IllegalArgumentException("Duplicate Java binding for RPC procedure: " + method);
        }
        this.procedures.put(method.getName(), id);
    }

    private static XdrCodec<Object> require(XdrCodec<Object> codec, ResolvableType type) {
        if (codec == null) {
            throw new UnsupportedOperationException("No XDR mapping for " + type);
        }
        return codec;
    }

    public Object getResource() {
        return this.resource;
    }

    public long getProgram() {
        return this.program;
    }

    /** 程序、过程和 XDR 类型均读取本端点已准备的定义，不重新解析 IDL。 */
    public ObjectNode describeContract(ObjectNode description) {
        description.put("name", this.programName);
        description.put("program", this.program);
        description.put("version", this.version);
        ArrayNode methods = description.putArray("methods");
        ObjectNode types = description.putObject("types");
        Map<XdrIdlType, String> visited = new IdentityHashMap<>();
        for (OncRpcIdlProcedure procedure : this.definitions) {
            Method method = this.methods.get(procedure.id);
            ObjectNode operation = methods.addObject();
            operation.put("procedureId", procedure.id);
            operation.put("name", procedure.name);
            operation.put("javaSignature", ServiceMetaParser.method(method).getJavaSignature());
            ArrayNode order = operation.putArray("argIndexes");
            for (int index = 0; index < method.getParameterCount(); index++) {
                order.add(this.parameterIndex(method.getName(), index));
            }
            String requestType = this.describeType(procedure.input, procedure.name + ".input", types, visited);
            operation.put("requestType", requestType);
            String responseType = this.describeType(procedure.result, procedure.name + ".result", types, visited);
            operation.put("responseType", responseType);
        }
        return description;
    }

    private String describeType(XdrIdlType type, String reference, ObjectNode types, //
            Map<XdrIdlType, String> visited) {
        // 自定义 XdrCodec 可以没有 IDL 定义；描述明确保留为不透明类型，不猜测线上结构。
        if (type == null) {
            types.putObject(reference).put("kind", "opaque");
            return reference;
        }
        if (this.resource instanceof OncRpcIdlResource file) {
            type = file.document.resolve(type);
        }
        String previous = visited.get(type);
        if (previous != null) {
            return previous;
        }
        visited.put(type, reference);
        ObjectNode value = types.putObject(reference);
        value.put("name", type.name);
        value.put("kind", type.kind);
        if (type.element != null) {
            value.put("elementType", this.describeType(type.element, reference + ".element", types, visited));
        }
        if (type.discriminator != null) {
            describeMember(type.discriminator, value.putObject("discriminator"), reference, types, visited);
        }
        ArrayNode members = value.putArray("members");
        for (XdrIdlMember member : type.members) {
            describeMember(member, members.addObject(), reference, types, visited);
        }
        ArrayNode constants = value.putArray("values");
        for (Map.Entry<String, Integer> constant : type.enumeration.entrySet()) {
            ObjectNode entry = constants.addObject();
            entry.put("fieldId", constant.getValue());
            entry.put("name", constant.getKey());
        }
        value.put("fixed", type.fixed);
        value.put("length", type.length);
        return reference;
    }

    private void describeMember(XdrIdlMember member, ObjectNode value, String parent, ObjectNode types, //
            Map<XdrIdlType, String> visited) {
        value.put("name", member.name);
        value.put("type", this.describeType(member.type, parent + "." + member.name, types, visited));
        value.put("defaultArm", member.defaultArm);
        ArrayNode cases = value.putArray("cases");
        for (Long branch : member.cases) {
            cases.add(branch);
        }
    }

    public long getVersion() {
        return this.version;
    }

    public Map<Long, Method> getMethods() {
        return Collections.unmodifiableMap(this.methods);
    }

    public Method getMethod(String name) {
        return this.namedMethods.get(name);
    }

    public long getProcedure(String name) {
        return this.procedures.get(name);
    }

    XdrCodec<Object> parameter(String method, int index) {
        return this.parameters.get(method).get(index);
    }

    int parameterIndex(String method, int wireIndex) {
        int[] order = this.parameterOrder.get(method);
        return order == null ? wireIndex : order[wireIndex];
    }

    XdrCodec<Object> result(String method) {
        return this.results.get(method);
    }
}
