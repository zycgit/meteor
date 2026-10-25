/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

/** 官方 SDK 订单对端的独立进程网络配置，只依赖 JDK。 */
public final class OrderPeerSettings {
    private OrderPeerSettings() {
    }

    public static String bindHost() {
        return environment("METEOR_ORDER_BIND_HOST", "127.0.0.1");
    }

    public static int port() {
        return Integer.parseInt(environment("METEOR_ORDER_PORT", "8080"));
    }

    public static InetSocketAddress bindAddress() {
        return new InetSocketAddress(bindHost(), port());
    }

    public static String publishHost() {
        String peerHost = environment("METEOR_TEST_PEER_HOST", bindHost());
        return environment("METEOR_ORDER_PUBLISH_HOST", peerHost);
    }

    public static URI publishAddress(String scheme, int actualPort, String path) {
        return address(scheme, null, publishHost(), actualPort, path, null, null);
    }

    public static URI target(String defaultAddress) {
        // 调度端可以只传监听地址，协议入口补齐自身的方法挂载路径。
        URI defaults = URI.create(defaultAddress);
        URI configured = URI.create(environment("METEOR_ORDER_TARGET", defaultAddress));
        String path = configured.getPath();
        boolean rootPath = path != null && path.length() == 1 && path.charAt(0) == '/';
        if (path == null || path.isEmpty() || rootPath) {
            path = defaults.getPath();
        }
        String query = configured.getQuery();
        if (query == null) {
            query = defaults.getQuery();
        }
        return address(configured.getScheme(), configured.getUserInfo(), configured.getHost(),
                configured.getPort(), path, query, configured.getFragment());
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static URI address(String scheme, String userInfo, String host, int port, String path,
            String query, String fragment) {
        try {
            return new URI(scheme, userInfo, host, port, path, query, fragment);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Invalid order peer address", failure);
        }
    }
}
