/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.soap;
import jakarta.xml.ws.Endpoint;
import net.hasor.example.OrderExampleUtils;

/** 使用 Eclipse Metro 发布 SOAP 订单服务，运行时不加载 Meteor。 */
public final class SdkProviderMain {
    private SdkProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // Metro 发布订单服务，同时生成 WSDL。
        Endpoint endpoint = Endpoint.publish("http://127.0.0.1:8080/soap/orders", new MetroOrderServiceImpl());
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(endpoint::stop, "metro-order-provider-shutdown"));
            System.out.println("SDK order provider is ready: http://127.0.0.1:8080/soap/orders");

            // Ctrl+C 时关闭服务。
            OrderExampleUtils.awaitShutdown();
        } finally {
            endpoint.stop();
        }
    }
}
