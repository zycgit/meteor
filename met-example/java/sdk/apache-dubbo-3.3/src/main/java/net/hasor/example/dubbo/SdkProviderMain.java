/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.dubbo;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;

/** 使用 Apache Dubbo SDK 发布 dubbo 订单服务。 */
public final class SdkProviderMain {
    private SdkProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 创建独立的官方 SDK 应用，直连示例不连接注册中心或启动管理端口。
        System.setProperty("dubbo.application.logger", "jdk");
        System.setProperty("dubbo.metadata-service.export-port", "-1");
        ApplicationConfig application = new ApplicationConfig("dubbo-sdk-order-provider");
        application.setQosEnable(false);
        application.setMetadataType("local");
        RegistryConfig registry = new RegistryConfig(RegistryConfig.NO_AVAILABLE);
        DubboBootstrap bootstrap = DubboBootstrap.getInstance().application(application).registry(registry);

        // 使用官方 ServiceConfig 发布统一订单业务。
        ProtocolConfig protocol = new ProtocolConfig("dubbo", 8080);
        protocol.setHost("0.0.0.0");
        protocol.setRegister(false);
        protocol.setSerialization("hessian2");
        ServiceConfig<OrderDemoService> service = new ServiceConfig<>();
        service.setInterface(OrderDemoService.class);
        service.setGroup("default");
        service.setVersion("1.0.0");
        service.setRef(new OrderDemoServiceImpl());
        service.setProtocol(protocol);
        service.setRegister(false);
        service.setScope("remote");

        // 入口持有 SDK 应用，进程退出时关闭由它创建的服务和网络资源。
        Runtime.getRuntime().addShutdownHook(new Thread(bootstrap::destroy, "dubbo-sdk-shutdown"));
        try {
            bootstrap.protocol(protocol).service(service).start();
            System.out.println("SDK order provider is ready: dubbo://127.0.0.1:8080/");
            OrderExampleUtils.awaitShutdown();
        } finally {
            bootstrap.destroy();
        }
    }
}
