/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.dubbo;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.peer.sdk.OrderPeerSettings;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;

/** 使用 Apache Dubbo SDK 向原生 ZooKeeper 注册中心发布订单服务。 */
public final class SdkRegistryProviderMain {
    private SdkRegistryProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 原生 SDK 使用接口级注册，并将 ZooKeeper 连接交给 DubboBootstrap 管理。
        System.setProperty("dubbo.application.logger", "jdk");
        System.setProperty("dubbo.metadata-service.export-port", "-1");
        ApplicationConfig application = new ApplicationConfig("dubbo-sdk-registry-order-provider");
        application.setQosEnable(false);
        application.setMetadataType("local");
        application.setRegisterMode("interface");
        RegistryConfig registry = new RegistryConfig("zookeeper://" + arguments[0]);
        registry.setRegisterMode("interface");
        DubboBootstrap bootstrap = DubboBootstrap.getInstance().application(application).registry(registry);

        // 发布与 Meteor 相同的接口、分组和版本。
        // SDK 用 -1 随机选端口；0 会回退为协议默认端口。
        int requestedPort = OrderPeerSettings.port();
        int sdkPort = requestedPort == 0 ? -1 : requestedPort;
        ProtocolConfig protocol = new ProtocolConfig("dubbo", sdkPort);
        protocol.setHost(OrderPeerSettings.bindHost());
        String publishHost = System.getenv("METEOR_ORDER_PUBLISH_HOST");
        if (publishHost == null || publishHost.isBlank()) {
            publishHost = System.getenv("METEOR_TEST_PEER_HOST");
        }
        if (publishHost != null && !publishHost.isBlank()) {
            System.setProperty("DUBBO_IP_TO_REGISTRY", publishHost);
        }
        protocol.setSerialization("hessian2");
        ServiceConfig<OrderDemoService> service = new ServiceConfig<>();
        service.setInterface(OrderDemoService.class);
        service.setGroup(arguments.length > 1 ? arguments[1] : "default");
        service.setVersion("1.0.0");
        service.setRef(new OrderDemoServiceImpl());
        service.setProtocol(protocol);
        service.setScope("remote");

        // 入口持有 SDK 应用，停止进程时撤销注册并关闭网络资源。
        Runtime.getRuntime().addShutdownHook(new Thread(bootstrap::destroy, "dubbo-sdk-registry-shutdown"));
        try {
            bootstrap.protocol(protocol).service(service).start();
            int actualPort = service.getExportedUrls().get(0).getPort();
            System.out.println("SDK order provider is ready: "
                    + OrderPeerSettings.publishAddress("dubbo", actualPort, "/"));
            OrderExampleUtils.awaitShutdown();
        } finally {
            bootstrap.destroy();
        }
    }
}
