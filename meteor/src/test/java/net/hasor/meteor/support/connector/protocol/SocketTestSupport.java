/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.support.connector.protocol;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ConnectorManager;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolTestAccess;

/** SPI 单元测试的配置与上下文桩；初始化和关闭由测试方法负责。 */
public final class SocketTestSupport {
    private SocketTestSupport() {
    }

    public static ConnectorConfig tcpConfig() {
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        return config("tcp", "raw", "rawprotocol", options, 0);
    }

    public static ConnectorConfig udpConfig(int maxDatagramSize, String protocol) throws SocketException {
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        options.put("maxDatagramSize", Integer.toString(maxDatagramSize));
        return config("udp", "udp", protocol, options, availableUdpPort());
    }

    public static int availableUdpPort() throws SocketException {
        // Cobble 只有 TCP 端口检测；用临时 UDP socket 选择显式非零监听端口。
        InetSocketAddress loopback = new InetSocketAddress("127.0.0.1", 0);
        try (DatagramSocket available = new DatagramSocket(loopback)) {
            return available.getLocalPort();
        }
    }

    private static ConnectorConfig config(//
            String transport, String scheme, String protocol, Map<String, String> options, int port) {
        InterAddress local = InterAddress.forBinding(transport, "127.0.0.1", port, "default");
        ProtocolConfig mount = ProtocolTestAccess.config(scheme, scheme, protocol, options);
        List<ProtocolConfig> mounts = Collections.singletonList(mount);
        return new ConnectorConfig(scheme, local, options, mounts, true);
    }

    public static ConnectorManager manager(ConnectorConfig config) {
        // 上下文桩只提供连接器实际依赖的配置和加载器，不模拟网络行为。
        ClassLoader loader = SocketTestSupport.class.getClassLoader();
        InvocationHandler settingsHandler = (proxy, method, args) -> {
            if (StringUtils.equals("getConnectorConfigs", method.getName())) {
                return Collections.singletonList(config);
            }
            if (StringUtils.equals("getDefaultTimeout", method.getName())) {
                return 10000;
            }
            throw new AssertionError(method);
        };
        Class<?>[] settingsInterfaces = { MetSettings.class };
        MetSettings settings = (MetSettings) Proxy.newProxyInstance(loader, settingsInterfaces, settingsHandler);
        InvocationHandler contextHandler = (proxy, method, args) -> {
            if (StringUtils.equals("getSettings", method.getName())) {
                return settings;
            }
            if (StringUtils.equals("getClassLoader", method.getName())) {
                return loader;
            }
            throw new AssertionError(method);
        };
        Class<?>[] contextInterfaces = { MetContext.class };
        MetContext context = (MetContext) Proxy.newProxyInstance(loader, contextInterfaces, contextHandler);

        // Manager 在 init 中使用上下文类加载器发现 SPI 并编译端点计划。
        return new ConnectorManager(context);
    }
}
