/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.ConstructorUtils;
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
        Class<?> processor = null;
        for (Class<?> nested : generatedService.getDeclaredClasses()) {
            if (StringUtils.equals(nested.getSimpleName(), "Iface") && nested.isInterface()) {
                iface = nested;
            }
            boolean processorName = StringUtils.equals(nested.getSimpleName(), "Processor");
            if (processorName && TBaseProcessor.class.isAssignableFrom(nested)) {
                processor = nested;
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
}
