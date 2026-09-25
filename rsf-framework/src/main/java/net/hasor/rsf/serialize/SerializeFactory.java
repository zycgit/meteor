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
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.rsf.serialize.coder.HessianSerializeCoder;
import net.hasor.rsf.serialize.coder.HproseSerializeCoder;
import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
import net.hasor.rsf.serialize.coder.JsonSerializeCoder;

/** Registry of named, reusable serialization coders, independent of the RPC container. */
public class SerializeFactory {
    private final Map<String, SerializeCoder> coderMap = new ConcurrentHashMap<>();
    private final ClassLoader                 classLoader;

    /** Creates an empty registry using the context class loader. */
    public SerializeFactory() {
        this(defaultClassLoader());
    }

    /** Creates an empty registry using the supplied business class loader. */
    public SerializeFactory(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    /** Returns null for an unknown name. Names remain case sensitive. */
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

    /** Creates a registry containing Java, Json, Hessian and Hprose. */
    public static SerializeFactory createFactory() {
        return createFactory(defaultClassLoader());
    }

    /** Creates the built-in registry with the supplied business class loader. */
    public static SerializeFactory createFactory(ClassLoader classLoader) {
        SerializeFactory factory = new SerializeFactory(classLoader);
        factory.registerSerializeCoder("Java", new JavaSerializeCoder());
        factory.registerSerializeCoder("Json", new JsonSerializeCoder());
        factory.registerSerializeCoder("Hessian", new HessianSerializeCoder());
        factory.registerSerializeCoder("Hprose", new HproseSerializeCoder());
        return factory;
    }

    /** Creates a registry from name-to-class-name mappings, without framework configuration APIs. */
    public static SerializeFactory createFactory(Map<String, String> coderClasses, ClassLoader classLoader) {
        Objects.requireNonNull(coderClasses, "coderClasses");
        SerializeFactory factory = new SerializeFactory(classLoader);
        for (Map.Entry<String, String> entry : coderClasses.entrySet()) {
            try {
                Class<? extends SerializeCoder> type = classLoader.loadClass(entry.getValue().trim()).asSubclass(SerializeCoder.class);
                factory.registerSerializeCoder(entry.getKey(), type.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalArgumentException("Cannot initialize serialize coder '" + entry.getKey() + "': " + entry.getValue(), e);
            }
        }
        return factory;
    }

    /** Chooses the context class loader, falling back when the calling thread has none. */
    public static ClassLoader defaultClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? SerializeFactory.class.getClassLoader() : loader;
    }
}
