/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.discovery;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import net.hasor.cobble.StringUtils;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;

/** 注册发现模式复用订单契约；应用接入只配置存储地址与存储凭据。 */
public final class RegistryDiscoveryExample {
    private RegistryDiscoveryExample() {
    }

    /** 服务端开启共享 TCP、HTTP 端口，自动向注册中心上报全部协议端点。 */
    public static MetConfiguration provider(RegistryConfiguration registry, String host, int tcpPort, int httpPort) {
        MetConfiguration configuration = DirectRpcExample.server(host, tcpPort, httpPort);
        configuration.setApplicationName("order-provider");
        configuration.addExtension(registry);
        return configuration;
    }

    /** 业务出口选择一个协议，RSF 出口用于内部管理绑定；两者都不监听或指定远端地址。 */
    public static MetConfiguration consumer(RegistryConfiguration registry, String protocol) {
        MetConfiguration configuration = DirectRpcExample.client(protocol);

        // 注册中心扩展的内部管理服务使用 RSF；业务订阅仍只选择调用方指定的协议。
        if (!StringUtils.equalsIgnoreCase(protocol, "rsf")) {
            configuration.connector("tcp").protocol("rsf");
        }
        configuration.setApplicationName("order-consumer");
        configuration.addExtension(registry);
        return configuration;
    }

    /** 地址示例为 nacos://127.0.0.1:8848 或 zk://127.0.0.1:2181；认证参数允许不提供。 */
    public static RegistryConfiguration registry(String typedAddress, String username, String password) {
        URI location = URI.create(typedAddress);
        String type = location.getScheme();
        String address = location.getRawAuthority();
        if (StringUtils.isBlank(type) || StringUtils.isBlank(address)) {
            throw new IllegalArgumentException("Registry address must include its type and server address");
        }

        // 业务 RPC 地址不参与此配置，凭据通过独立参数传递。
        boolean extraLocation = StringUtils.isNotBlank(location.getRawPath()) || location.getRawQuery() != null;
        if (extraLocation || location.getRawFragment() != null || address.indexOf('@') >= 0) {
            throw new IllegalArgumentException("Registry address must use <nacos|zk|zookeeper>://<servers>");
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "nacos" -> new NacosRegistryConfiguration() //
                    .setAddress(address).setUsername(username).setPassword(password);
            case "zk", "zookeeper" -> new ZookeeperRegistryConfiguration() //
                    .setAddress(address).setUsername(username).setPassword(password);
            default -> throw new IllegalArgumentException("Unsupported registry type: " + type);
        };
    }

    /** 有界观察自动发现的地址本，不主动同步、不发送探测业务调用。 */
    public static void awaitDiscovery(MetContext context, MetBindInfo<?> service) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            List<InterAddress> addresses = context.getUpdater().queryAvailableAddresses(service.getBindID());
            if (addresses != null && !addresses.isEmpty()) {
                return;
            }

            // 每次重新读取公开地址本；等待有界且响应调用线程的中断。
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while waiting for registry discovery");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("No provider discovered for " + service.getBindID());
            }
            long pause = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10));
            LockSupport.parkNanos(pause);
        }
    }
}
