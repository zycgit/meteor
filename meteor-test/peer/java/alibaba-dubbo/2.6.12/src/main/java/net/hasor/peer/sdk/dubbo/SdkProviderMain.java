/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.peer.sdk.dubbo;
import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.ServiceConfig;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.peer.sdk.OrderPeerSettings;

/** 由原 com.alibaba 命名空间的 Dubbo 2.6 SDK 提供相同订单服务。 */
public final class SdkProviderMain {
    private SdkProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 只发布远程业务监听，关闭旧 SDK 的管理端口和注册发现。
        System.setProperty("dubbo.application.logger", "jdk");
        System.setProperty("dubbo.service.shutdown.wait", "1000");
        ApplicationConfig application = new ApplicationConfig("dubbo-classic-order-provider");
        application.setQosEnable(false);
        // 显式传入操作系统分配的端口，不让 SDK 的端口探测回退到固定默认值。
        int sdkPort = OrderPeerSettings.bindPort();
        ProtocolConfig protocol = new ProtocolConfig("dubbo", sdkPort);
        protocol.setHost(OrderPeerSettings.bindHost());
        protocol.setSerialization("hessian2");
        protocol.setRegister(false);

        // 服务身份和 DTO 与 Meteor 端相同，协议编解码仍由原 SDK 完成。
        ServiceConfig<OrderDemoService> service = new ServiceConfig<>();
        service.setApplication(application);
        service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
        service.setInterface(OrderDemoService.class);
        service.setGroup("default");
        service.setVersion("1.0.0");
        service.setProtocol(protocol);
        service.setRef(new OrderDemoServiceImpl());
        service.setRegister(false);
        service.setScope("remote");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            service.unexport();
            protocol.destroy();
        }, "dubbo-classic-sdk-shutdown"));
        try {
            service.export();
            int actualPort = service.getExportedUrls().get(0).getPort();
            String target = OrderPeerSettings.publishAddress("dubbo", actualPort, "/").toString();
            OrderExampleUtils.ready(target, "SDK order provider is ready: ");
            OrderExampleUtils.awaitShutdown();
        } finally {
            service.unexport();
            protocol.destroy();
        }
    }
}
