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
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;

/** Local ONC RPC contract and explicit XDR codecs. Configure before publishing or subscribing the service. */
public final class OncRpcServiceResource {
    private final Class<?>                   serviceType;
    private final long                       program;
    private       long                       version = 1L;
    private final Map<Long, Method>          methods;
    private final Map<String, Method>        namedMethods;
    private final Map<Class<?>, XdrCodec<?>> codecs  = new LinkedHashMap<>();

    public OncRpcServiceResource(Class<?> serviceType) {
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType");
        if (!serviceType.isInterface()) {
            throw new IllegalArgumentException("ONC RPC service type must be an interface");
        }

        long id = ServiceMetaParser.parse(serviceType).getId();
        if (id == -1L) {
            throw new IllegalArgumentException("ONC RPC service must declare MetService.id");
        }
        this.program = unsigned(id, "Program");

        // Resolve the wire procedures once; no implementation object is retained or invoked here.
        Map<Long, Method> procedures = new LinkedHashMap<>();
        Map<String, Method> names = new LinkedHashMap<>();
        for (Method method : serviceType.getMethods()) {
            boolean generated = method.isBridge() || method.isSynthetic();
            if (Modifier.isStatic(method.getModifiers()) || generated) {
                continue;
            }
            MethodMeta mapping = ServiceMetaParser.method(serviceType, method);
            if (mapping.isIgnored()) {
                continue;
            }
            String name = method.getName();
            Method previous = names.putIfAbsent(name, method);
            if (previous != null) {
                if (!Arrays.equals(previous.getParameterTypes(), method.getParameterTypes())) {
                    throw new IllegalArgumentException("ONC RPC does not support overloaded methods: " + name);
                }
                long previousId = ServiceMetaParser.method(serviceType, previous).getId();
                long currentId = mapping.getId();
                boolean different = previousId == 0 || currentId == 0 || previousId != currentId;
                if (different) {
                    throw new IllegalArgumentException("Ambiguous inherited ONC RPC procedure: " + method.getName());
                }
                continue;
            }
            long number = unsigned(mapping.getId(), "Procedure");
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
        if (!serviceType.isInterface() || ServiceMetaParser.parse(serviceType).getId() == -1L) {
            return null;
        }

        // 显式注解损坏仍由构造阶段报错，缺少协议契约只表示不支持该接口。
        Map<String, Method> names = new LinkedHashMap<>();
        for (Method method : serviceType.getMethods()) {
            boolean generated = method.isBridge() || method.isSynthetic();
            if (Modifier.isStatic(method.getModifiers()) || generated) {
                continue;
            }
            MethodMeta mapping = ServiceMetaParser.method(serviceType, method);
            if (mapping.isIgnored()) {
                continue;
            }
            if (mapping.getId() == 0) {
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

    /** 设置本服务的 ONC 程序版本；发布和订阅前配置，默认 1，与公共服务版本无关。 */
    public void setVersion(long version) {
        long selected = unsigned(version, "Version");
        if (selected == 0) {
            throw new IllegalArgumentException("ONC RPC program version must not be zero");
        }
        this.version = selected;
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

    private static long unsigned(long number, String description) {
        if (number < 0 || number > 0xffffffffL) {
            throw new IllegalArgumentException(description + " must be an unsigned 32-bit integer");
        }
        return number;
    }
}
