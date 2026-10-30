/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.xmlrpc;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;

/** Apache XML-RPC 官方 Java SDK 对端；请求和响应完全由 SDK 编解码。 */
public final class ApacheXmlRpcPeer {
    private ApacheXmlRpcPeer() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] arguments) throws Exception {
        requireIsolatedClasspath();
        Map<String, Object> request;
        try (ObjectInputStream stream = new ObjectInputStream(Files.newInputStream(Path.of(arguments[0])))) {
            request = (Map<String, Object>) stream.readObject();
        }

        // 沿用 Apache 文档的标准 HTTP 客户端，不启用厂商扩展或本地短路传输。
        XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
        config.setServerURL(URI.create((String) request.get("endpoint")).toURL());
        config.setConnectionTimeout(5000);
        config.setReplyTimeout(10000);
        config.setEnabledForExtensions(false);
        XmlRpcClient client = new XmlRpcClient();
        client.setConfig(config);
        String method = (String) request.get("method");
        Object[] parameters = (Object[]) request.get("parameters");
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            response.put("result", client.execute(method, parameters));
        } catch (XmlRpcException failure) {
            response.put("errorCode", failure.code);
            response.put("errorMessage", failure.getMessage());
        }

        // 协议 fault 转为 JDK 值供宿主断言，SDK 自身始终只存在于独立 JVM。
        try (ObjectOutputStream stream = new ObjectOutputStream(Files.newOutputStream(Path.of(arguments[1])))) {
            stream.writeObject(response);
        }
        System.out.println("RESULT apache-xmlrpc completed");
    }

    private static void requireIsolatedClasspath() throws ClassNotFoundException {
        try {
            Class.forName("net.hasor.meteor.MetContext", false, ApacheXmlRpcPeer.class.getClassLoader());
        } catch (ClassNotFoundException expected) {
            return;
        }
        throw new IllegalStateException("Apache XML-RPC peer must not load Meteor runtime");
    }
}
