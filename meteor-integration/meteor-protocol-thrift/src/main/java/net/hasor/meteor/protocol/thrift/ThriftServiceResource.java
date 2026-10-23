/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.ConstructorUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.ParameterMeta;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.protocol.thrift.codec.ThriftCodec;
import net.hasor.meteor.protocol.thrift.codec.ThriftServiceSchema;
import org.apache.thrift.ProcessFunction;
import org.apache.thrift.TBaseProcessor;

/**
 * Local metadata from an Apache Thrift generated service. The generated Processor is created without an
 * implementation only to obtain its public metadata; invocation remains owned by Meteor's dispatcher.
 */
public final class ThriftServiceResource {
    private final Class<?>                              generatedService;
    private final Class<?>                              ifaceType;
    private final Map<String, ProcessFunction<?, ?, ?>> functions;
    private final Map<String, Method>                   methods;

    public ThriftServiceResource(Class<?> generatedService) {
        this.generatedService = Objects.requireNonNull(generatedService, "generatedService");
        Class<?> iface = null;
        Class<?> processor = processorType(generatedService);
        for (Class<?> nested : generatedService.getDeclaredClasses()) {
            if (StringUtils.equals(nested.getSimpleName(), "Iface") && nested.isInterface()) {
                iface = nested;
            }
        }
        if (iface == null || processor == null) {
            String typeName = generatedService.getName();
            throw new IllegalArgumentException("Expected an Apache Thrift generated service: " + typeName);
        }
        this.ifaceType = iface;
        try {
            Class<?>[] signature = { iface };
            Object metadata = ConstructorUtils.invokeExactConstructor(processor, new Object[] { null }, signature);
            TBaseProcessor<?> nativeProcessor = (TBaseProcessor<?>) metadata;
            Map<String, ProcessFunction<?, ?, ?>> functions = new LinkedHashMap<>();
            nativeProcessor.getProcessMapView().forEach(functions::put);
            this.functions = Collections.unmodifiableMap(functions);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot read generated Thrift Processor metadata", failure);
        }
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Method method : iface.getMethods()) {
            if (methods.putIfAbsent(method.getName(), method) != null) {
                throw new IllegalArgumentException("Thrift Iface contains overloaded methods: " + method.getName());
            }
        }
        if (!methods.keySet().equals(this.functions.keySet())) {
            throw new IllegalArgumentException("Generated Thrift Iface and Processor metadata disagree");
        }
        this.methods = Collections.unmodifiableMap(methods);
    }

    public Class<?> getGeneratedService() {
        return this.generatedService;
    }

    public Class<?> getIfaceType() {
        return this.ifaceType;
    }

    public String getServiceName() {
        return this.generatedService.getSimpleName();
    }

    /** Read-only SDK metadata; getResult/process must never be used to invoke a Meteor service. */
    public Map<String, ProcessFunction<?, ?, ?>> getFunctions() {
        return this.functions;
    }

    public Map<String, Method> getMethods() {
        return this.methods;
    }

    /** 发布时选择契约来源；编解码阶段只持有已经编译的协议定义。 */
    static ThriftServiceSchema compile(ProtocolConfig protocol, ProtocolMeta meta) {
        MetBindInfo<?> service = meta.getService();
        ThriftCodec.validateOptions(protocol);
        String serializer = service.getSerializeType();
        if (serializer != null && !StringUtils.equalsIgnoreCase(serializer, "thrift")) {
            return null;
        }

        Object bound = meta.getBindResource();
        boolean supportedResource = bound instanceof ThriftServiceResource || bound instanceof ThriftIdlResource;
        if (bound != null && !supportedResource) {
            throw new IllegalArgumentException("Thrift requires a ThriftIdlResource or ThriftServiceResource");
        }

        ThriftServiceSchema schema;
        if (bound instanceof ThriftIdlResource idl) {
            schema = idl.bind(service);
        } else {
            ThriftServiceResource resource = null;
            if (bound instanceof ThriftServiceResource thrift) {
                resource = thrift;
            }
            if (resource == null) {
                Class<?> generatedIface = inferredGeneratedIface(service.getBindType());
                if (generatedIface != null) {
                    resource = new ThriftServiceResource(generatedIface.getDeclaringClass());
                }
            }
            if (resource == null) {
                // 注解契约不占用 bindResource，其他协议的绑定资源不参与 Thrift 映射。
                // 普通多协议服务缺少必要编号时只退出 Thrift，显式错误不再统一吞掉。
                for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
                    for (Parameter parameter : method.getParameters()) {
                        ParameterMeta mapping = ServiceMetaParser.parameter(parameter);
                        if (mapping.getId() == 0) {
                            return null;
                        }
                    }
                }
                schema = ThriftServiceSchema.annotated(service);
            } else {
                schema = ThriftServiceSchema.generated(//
                        service, resource.getServiceName(), resource.getMethods(), resource.getFunctions());
            }
        }

        String alias = ThriftCodec.alias(protocol, service, schema);
        if (StringUtils.isBlank(alias) || alias.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Thrift service alias must be nonempty and cannot contain ':'");
        }
        return ThriftCodec.selected(protocol.option("service", null), alias, schema) ? schema : null;
    }

    private static Class<?> inferredGeneratedIface(Class<?> type) {
        boolean generatedIface = type.isInterface() && StringUtils.equals(type.getSimpleName(), "Iface");
        Class<?> service = type.getDeclaringClass();
        if (generatedIface && service != null && processorType(service) != null) {
            return type;
        }

        Class<?> selected = null;
        for (Class<?> parent : type.getInterfaces()) {
            Class<?> candidate = inferredGeneratedIface(parent);
            if (candidate == null) {
                continue;
            }
            if (selected == null || selected.isAssignableFrom(candidate)) {
                selected = candidate;
            } else if (!candidate.isAssignableFrom(selected)) {
                return null;
            }
        }
        return selected;
    }

    private static Class<?> processorType(Class<?> generatedService) {
        for (Class<?> nested : generatedService.getDeclaredClasses()) {
            boolean processorName = StringUtils.equals(nested.getSimpleName(), "Processor");
            if (processorName && TBaseProcessor.class.isAssignableFrom(nested)) {
                return nested;
            }
        }
        return null;
    }
}