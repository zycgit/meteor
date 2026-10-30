/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.soap;
import com.sun.net.httpserver.HttpServer;
import javax.xml.ws.Endpoint;
import net.hasor.example.OrderExampleUtils;
import net.hasor.peer.sdk.OrderPeerSettings;

/** 使用 Eclipse Metro 发布 SOAP 订单服务，运行时不加载 Meteor。 */
public final class SdkProviderMain {
    private SdkProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // Metro 发布订单服务，同时生成 WSDL。
        HttpServer server = HttpServer.create(OrderPeerSettings.bindAddress(), 0);
        Endpoint endpoint = Endpoint.create(new MetroOrderServiceImpl());
        try {
            endpoint.publish(server.createContext("/soap/orders"));
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                endpoint.stop();
                server.stop(0);
            }, "metro-order-provider-shutdown"));
            System.out.println("SDK order provider is ready: "
                    + OrderPeerSettings.publishAddress("http", server.getAddress().getPort(), "/soap/orders"));

            // Ctrl+C 时关闭服务。
            OrderExampleUtils.awaitShutdown();
        } finally {
            endpoint.stop();
            server.stop(0);
        }
    }
}
