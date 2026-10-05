/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.serialize;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentSkipListMap;

/** Registry of named, reusable serialization coders, independent of the RPC container. */
public class SerializeFactory {
    private final Map<String, SerializeCoder> coderMap = new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);
    private final ClassLoader                 classLoader;

    /** Creates an empty registry using the context class loader. */
    public SerializeFactory() {
        this(defaultClassLoader());
    }

    /** Creates an empty registry using the supplied business class loader. */
    public SerializeFactory(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    /** Looks up names without regard to case; returns null for an unknown name. */
    public SerializeCoder getSerializeCoder(String codeName) {
        return codeName == null ? null : this.coderMap.get(codeName);
    }

    /** Initializes before publication; a failed replacement leaves the old coder registered. */
    public void registerSerializeCoder(String codeName, SerializeCoder coder) {
        if (codeName == null || codeName.trim().isEmpty()) {
            throw new IllegalArgumentException("codeName must not be blank");
        }
        Objects.requireNonNull(coder, "coder").initCoder(this.classLoader);
        this.coderMap.put(codeName, coder);
    }

    /** Discovers named coders through SPI using the context class loader. */
    public static SerializeFactory createFactory() {
        return createFactory(defaultClassLoader());
    }

    /** Discovers and initializes coders for this registry; duplicate SPI names are rejected. */
    public static SerializeFactory createFactory(ClassLoader classLoader) {
        SerializeFactory factory = new SerializeFactory(classLoader);
        try {
            for (SerializeCoder coder : ServiceLoader.load(SerializeCoder.class, classLoader)) {
                String name = coder.name();
                SerializeCoder previous = factory.getSerializeCoder(name);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate serialize coder '" + name + "': " + previous.getClass().getName() + " and " + coder.getClass().getName());
                }
                try {
                    factory.registerSerializeCoder(name, coder);
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException("Cannot initialize serialize coder '" + name + "': " + coder.getClass().getName(), failure);
                }
            }
        } catch (ServiceConfigurationError failure) {
            throw new IllegalArgumentException("Cannot discover serialize coders through SPI", failure);
        }
        return factory;
    }

    /** Chooses the context class loader, falling back when the calling thread has none. */
    public static ClassLoader defaultClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? SerializeFactory.class.getClassLoader() : loader;
    }
}
