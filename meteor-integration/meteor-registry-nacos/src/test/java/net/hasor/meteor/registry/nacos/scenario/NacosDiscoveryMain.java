/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos.scenario;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.setting.DefaultSettings;
import net.hasor.cobble.setting.Settings;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;

/** 直接运行 main，连接外部 Nacos，发布、发现并调用一个服务。 */
public class NacosDiscoveryMain {
    public static void main(String[] args) throws Exception {
        // 从配置文件连接真实 Nacos，服务端和客户端使用同一份注册中心配置。
        NacosRegistryConfiguration conf = createRegistryConfiguration();
        MetConfiguration provider = new MetConfiguration();
        provider.addExtension(conf);
        provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");

        MetConfiguration consumer = new MetConfiguration();
        consumer.addExtension(conf);
        consumer.connector("tcp").protocol("rsf");

        // 启动两个独立应用，分别发布和订阅同一个接口。
        try (MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, value -> "echo:" + value) //
                    .register();
            MetBindInfo<EchoService> service = client.publisher().rsfService(EchoService.class).register();

            // 等待自动发现，然后通过真实 TCP 连接完成一次调用。
            String serviceId = service.getBindID();
            awaitProvider(client, serviceId);
            EchoService remote = client.getRsfClient().getRemote(service);
            String result = remote.echo("Meteor");
            if (!StringUtils.equals("echo:Meteor", result)) {
                throw new IllegalStateException("Unexpected RPC result: " + result);
            }
            System.out.println("RPC 调用成功：" + result);
            System.out.println("按 Enter 关闭服务端和客户端。");
            System.in.read();
        }
    }

    private static NacosRegistryConfiguration createRegistryConfiguration() throws Exception {
        // 从用户配置读取连接参数，不使用环境变量或默认本机地址。
        Settings settings = new DefaultSettings("classpath:/nacos-test.properties");
        String address = settings.getString("nacos.address");
        if (StringUtils.isBlank(address)) {
            throw new IllegalArgumentException("请在 nacos-test.properties 中配置 nacos.address");
        }

        // 构造配置本身不连接外部服务，由应用扩展管理存储会话。
        NacosRegistryConfiguration registry = new NacosRegistryConfiguration();
        registry.setAddress(address);
        registry.setNamespace(StringUtils.trimToNull(settings.getString("nacos.namespace", "meteor")));
        registry.setGroup(settings.getString("nacos.group", "meteor"));
        registry.setUsername(StringUtils.trimToNull(settings.getString("nacos.username")));
        registry.setPassword(settings.getString("nacos.password"));
        return registry;
    }

    private static void awaitProvider(MetContext client, String serviceId) throws Exception {
        // 仅等待自动发现结果，不通过主动拉取替代存储订阅。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (client.getUpdater().queryAvailableAddresses(serviceId).isEmpty()) {
            if (System.nanoTime() >= deadline) {
                throw new TimeoutException("20 秒内未发现服务提供者：" + serviceId);
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }
}
