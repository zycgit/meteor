/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.jsonrpc;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;

/** 使用 jsonrpc4j 原生 Java 客户端；不加载 Meteor、协议插件或手工 JSON 编解码器。 */
public final class JsonRpc4jPeer {
    private JsonRpc4jPeer() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] arguments) throws Throwable {
        requireIsolatedClasspath();
        Map<String, Object> request;
        try (ObjectInputStream stream = new ObjectInputStream(Files.newInputStream(Path.of(arguments[0])))) {
            request = (Map<String, Object>) stream.readObject();
        }

        // SDK 独立完成请求 ID、JSON 编解码、HTTP 传输和错误响应解析。
        URI endpoint = URI.create((String) request.get("endpoint"));
        JsonRpcHttpClient client = new JsonRpcHttpClient(endpoint.toURL());
        client.setConnectionTimeoutMillis(5000);
        client.setReadTimeoutMillis(10000);
        String method = (String) request.get("method");
        Object parameters = request.get("parameters");
        Class<?> resultType = (Class<?>) request.get("resultType");
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            response.put("result", client.invoke(method, parameters, resultType));
        } catch (JsonRpcClientException failure) {
            response.put("errorCode", failure.getCode());
            response.put("errorMessage", failure.getMessage());
        }

        // SDK 专属类型留在本进程，只向测试返回可断言的普通值。
        try (ObjectOutputStream stream = new ObjectOutputStream(Files.newOutputStream(Path.of(arguments[1])))) {
            stream.writeObject(response);
        }
        System.out.println("RESULT jsonrpc4j completed");
    }

    private static void requireIsolatedClasspath() throws ClassNotFoundException {
        try {
            Class.forName("net.hasor.meteor.MetContext", false, JsonRpc4jPeer.class.getClassLoader());
        } catch (ClassNotFoundException expected) {
            return;
        }
        throw new IllegalStateException("jsonrpc4j peer must not load Meteor runtime");
    }
}
