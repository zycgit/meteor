/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.triple;
import meteor.example.OrderDemoService;
import net.hasor.example.OrderExampleUtils;
import net.hasor.peer.sdk.OrderPeerSettings;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;

/** 使用 Apache Dubbo SDK 发布 triple 订单服务。 */
public final class SdkProviderMain {
    private SdkProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 创建独立的官方 SDK 应用，直连示例不连接注册中心或启动管理端口。
        System.setProperty("dubbo.application.logger", "jdk");
        System.setProperty("dubbo.metadata-service.export-port", "-1");
        ApplicationConfig application = new ApplicationConfig("triple-sdk-order-provider");
        application.setQosEnable(false);
        application.setMetadataType("local");
        RegistryConfig registry = new RegistryConfig(RegistryConfig.NO_AVAILABLE);
        DubboBootstrap bootstrap = DubboBootstrap.getInstance().application(application).registry(registry);

        // 使用官方 ServiceConfig 发布统一订单业务。
        // SDK 的 0 和 -1 都可能先探协议默认端口，显式传入操作系统分配的端口。
        int sdkPort = OrderPeerSettings.bindPort();
        ProtocolConfig protocol = new ProtocolConfig("tri", sdkPort);
        protocol.setHost(OrderPeerSettings.bindHost());
        String publishHost = System.getenv("METEOR_ORDER_PUBLISH_HOST");
        if (publishHost == null || publishHost.isBlank()) {
            publishHost = System.getenv("METEOR_TEST_PEER_HOST");
        }
        if (publishHost != null && !publishHost.isBlank()) {
            System.setProperty("DUBBO_IP_TO_REGISTRY", publishHost);
        }
        protocol.setRegister(false);
        ServiceConfig<OrderDemoService> service = new ServiceConfig<>();
        service.setInterface(OrderDemoService.class);
        service.setGroup("default");
        service.setVersion("1.0.0");
        service.setRef(new SdkOrderServiceImpl());
        service.setProtocol(protocol);
        service.setRegister(false);
        service.setScope("remote");
        service.setPath(OrderExampleUtils.SERVICE_NAME);

        // 入口持有 SDK 应用，进程退出时关闭由它创建的服务和网络资源。
        Runtime.getRuntime().addShutdownHook(new Thread(bootstrap::destroy, "triple-sdk-shutdown"));
        try {
            bootstrap.protocol(protocol).service(service).start();
            int actualPort = service.getExportedUrls().get(0).getPort();
            String target = OrderPeerSettings.publishAddress("http", actualPort, "/").toString();
            OrderExampleUtils.ready(target, "SDK order provider is ready: ");
            OrderExampleUtils.awaitShutdown();
        } finally {
            bootstrap.destroy();
        }
    }
}
