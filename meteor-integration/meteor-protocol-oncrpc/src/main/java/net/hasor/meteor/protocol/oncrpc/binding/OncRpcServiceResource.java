/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.binding;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.protocol.oncrpc.annotation.OncRpcProcedure;
import net.hasor.meteor.protocol.oncrpc.annotation.OncRpcProgram;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;

/** Local ONC RPC contract and explicit XDR codecs. Configure before publishing or subscribing the service. */
public final class OncRpcServiceResource {
    private final Class<?>                   serviceType;
    private final long                       program;
    private final long                       version;
    private final Map<Long, Method>          methods;
    private final Map<String, Method>        namedMethods;
    private final Map<Class<?>, XdrCodec<?>> codecs = new LinkedHashMap<>();

    public OncRpcServiceResource(Class<?> serviceType) {
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType");
        if (!serviceType.isInterface()) {
            throw new IllegalArgumentException("ONC RPC service type must be an interface");
        }
        OncRpcProgram declaration = program(serviceType);
        if (declaration == null) {
            throw new IllegalArgumentException("ONC RPC service must declare @OncRpcProgram");
        }
        this.program = unsigned(declaration.id(), "Program");
        this.version = unsigned(declaration.version(), "Version");
        if (this.version == 0) {
            throw new IllegalArgumentException("ONC RPC program version must not be zero");
        }

        // Resolve the wire procedures once; no implementation object is retained or invoked here.
        Map<Long, Method> procedures = new LinkedHashMap<>();
        Map<String, Method> names = new LinkedHashMap<>();
        for (Method method : serviceType.getMethods()) {
            boolean excluded = Modifier.isStatic(method.getModifiers()) || ServiceMetaParser.method(method).isIgnored();
            if (excluded || method.isBridge() || method.isSynthetic()) {
                continue;
            }
            String name = method.getName();
            Method previous = names.putIfAbsent(name, method);
            if (previous != null) {
                if (!Arrays.equals(previous.getParameterTypes(), method.getParameterTypes())) {
                    throw new IllegalArgumentException("ONC RPC does not support overloaded methods: " + name);
                }
                OncRpcProcedure previousId = previous.getAnnotation(OncRpcProcedure.class);
                OncRpcProcedure currentId = method.getAnnotation(OncRpcProcedure.class);
                boolean different = previousId == null || currentId == null || previousId.id() != currentId.id();
                if (different) {
                    throw new IllegalArgumentException("Ambiguous inherited ONC RPC procedure: " + method.getName());
                }
                continue;
            }
            OncRpcProcedure procedure = method.getAnnotation(OncRpcProcedure.class);
            if (procedure == null) {
                throw new IllegalArgumentException("ONC RPC method must declare @OncRpcProcedure: " + method.getName());
            }
            long number = unsigned(procedure.id(), "Procedure");
            if (number == 0) {
                throw new IllegalArgumentException("ONC RPC procedure zero is reserved for NULL");
            }
            if (procedures.putIfAbsent(number, method) != null) {
                throw new IllegalArgumentException("Duplicate ONC RPC procedure number: " + number);
            }
        }
        this.methods = Collections.unmodifiableMap(procedures);
        this.namedMethods = Collections.unmodifiableMap(names);
    }

    /** 普通接口缺少 ONC 声明或具有重载签名时，拒绝当前协议而保留其他发布方式。 */
    static OncRpcServiceResource infer(Class<?> serviceType) {
        if (!serviceType.isInterface() || program(serviceType) == null) {
            return null;
        }

        // 显式注解损坏仍由构造阶段报错，缺少协议契约只表示不支持该接口。
        Map<String, Method> names = new LinkedHashMap<>();
        for (Method method : serviceType.getMethods()) {
            boolean excluded = Modifier.isStatic(method.getModifiers()) || ServiceMetaParser.method(method).isIgnored();
            if (excluded || method.isBridge() || method.isSynthetic()) {
                continue;
            }
            if (!method.isAnnotationPresent(OncRpcProcedure.class)) {
                return null;
            }
            Method previous = names.putIfAbsent(method.getName(), method);
            if (previous != null && !Arrays.equals(previous.getParameterTypes(), method.getParameterTypes())) {
                return null;
            }
        }
        return new OncRpcServiceResource(serviceType);
    }

    public Class<?> getServiceType() {
        return this.serviceType;
    }

    public long getProgram() {
        return this.program;
    }

    public long getVersion() {
        return this.version;
    }

    public Map<Long, Method> getMethods() {
        return this.methods;
    }

    public Method getMethod(String name) {
        return this.namedMethods.get(name);
    }

    /** Override a Java type's wire mapping, for example an IDL struct, union, enum or unsigned number. */
    public <T> OncRpcServiceResource codec(Class<T> type, XdrCodec<T> codec) {
        this.codecs.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(codec, "codec"));
        return this;
    }

    public XdrCodec<?> getCodec(Class<?> type) {
        return this.codecs.get(type);
    }

    static OncRpcProgram program(Class<?> type) {
        OncRpcProgram selected = type.getDeclaredAnnotation(OncRpcProgram.class);
        if (selected != null) {
            return selected;
        }
        for (Class<?> parent : type.getInterfaces()) {
            OncRpcProgram inherited = program(parent);
            if (inherited == null) {
                continue;
            }
            boolean different = selected != null && //
                    (selected.id() != inherited.id() || selected.version() != inherited.version());
            if (different) {
                throw new IllegalArgumentException("Ambiguous inherited ONC RPC program");
            }
            selected = inherited;
        }
        return selected;
    }

    private static long unsigned(long number, String description) {
        if (number < 0 || number > 0xffffffffL) {
            throw new IllegalArgumentException(description + " must be an unsigned 32-bit integer");
        }
        return number;
    }
}
