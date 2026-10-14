/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.protocol.rsf.support;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.io.IOUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;

/** 历史报文与仅提供序列化器的无资源单元夹具。 */
public final class RsfFixtures {
    private RsfFixtures() {
    }

    public static byte[] fixture(String name) throws IOException {
        String path = "/rsf-v1-wire-fixtures/" + name;
        try (InputStream input = RsfFixtures.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing RSF fixture: " + path);
            }
            return IOUtils.toByteArray(input);
        }
    }

    public static MetContext serializationContext() {
        // 仅实现编解码访问的契约，误用完整应用能力会立即失败。
        ClassLoader loader = RsfFixtures.class.getClassLoader();
        InvocationHandler contextHandler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getClassLoader":
                    return loader;
                case "getSerializeCoder":
                    if (ObjectUtils.equals("Java", args[0])) {
                        return new JavaSerializeCoder();
                    }
                    return null;
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        };
        Class<?>[] interfaces = { MetContext.class };
        return (MetContext) Proxy.newProxyInstance(loader, interfaces, contextHandler);
    }
}
