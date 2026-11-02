/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.registry.dubbo;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.dubbo.DubboRegistryClient;
import net.hasor.meteor.registry.dubbo.DubboRegistryConfiguration;

/** 参数为已有注册中心地址，例如 zookeeper://127.0.0.1:2181。 */
public final class DubboRegistryExample {
    private DubboRegistryExample() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: DubboRegistryExample <registry-url>");
        }

        // 两个应用共用原生注册中心，消费方无需配置业务服务地址。
        DubboRegistryConfiguration providerRegistry = new DubboRegistryConfiguration().setAddress(args[0]);
        providerRegistry.setPublishHost("127.0.0.1");
        MetConfiguration provider = new MetConfiguration().addExtension(providerRegistry);
        provider.setApplicationName("meteor-dubbo-example-provider");
        provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
        DubboRegistryConfiguration consumerRegistry = new DubboRegistryConfiguration().setAddress(args[0]);
        MetConfiguration consumer = new MetConfiguration().addExtension(consumerRegistry);
        consumer.setApplicationName("meteor-dubbo-example-consumer");
        consumer.connector("tcp").protocol("dubbo");

        try (MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            // 容器启动后发布普通接口，注册扩展自动向原生注册中心登记服务。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(RegistryEchoService.class, value -> "echo:" + value) //
                    .register();

            // 通过公开扩展引用服务，业务 RPC 仍由 Meteor 的 Caller 和协议插件执行。
            DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
            MetBindInfo<RegistryEchoService> service = client.publisher() //
                    .rsfService(RegistryEchoService.class) //
                    .register();
            RegistryEchoService remote = registry.reference(service);

            // 新服务异步注册后，由订阅通知更新地址本；只在地址就绪后进行首次调用。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (client.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty()) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Dubbo service discovery did not become ready");
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
            System.out.println(remote.echo("Meteor"));
        }
    }
}
