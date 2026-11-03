/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.ServerSocket;

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

    /** SDK 不支持直接绑定端口零时，先在当前端点容器内申请一个操作系统临时端口。 */
    public static int bindPort() throws IOException {
        int requestedPort = port();
        if (requestedPort != 0) {
            return requestedPort;
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(bindHost(), 0));
            return socket.getLocalPort();
        }
    }

    public static String publishHost() {
        String peerHost = environment("METEOR_TEST_PEER_HOST", bindHost());
        return environment("METEOR_ORDER_PUBLISH_HOST", peerHost);
    }

    public static URI publishAddress(String scheme, int actualPort, String path) {
        return address(scheme, null, publishHost(), actualPort, path, null, null);
    }

    public static URI target(String defaultAddress) {
        // 旧入口只传监听地址时补默认目录；NXN 返回空路径或 / 时明确使用服务端根目录。
        URI defaults = URI.create(defaultAddress);
        URI configured = URI.create(environment("METEOR_ORDER_TARGET", defaultAddress));
        String path = configured.getPath();
        boolean rootPath = path != null && path.length() == 1 && path.charAt(0) == '/';
        boolean standardRequest = System.getenv("METEOR_NXN_REQUEST_FILE") != null;
        if (!standardRequest && (path == null || path.isEmpty() || rootPath)) {
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
