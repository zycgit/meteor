/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.jsonrpc;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ProtocolSettings;

/** 启动后发布与订阅普通 Java 接口；传入 ws 使用同一 HTTP 端口的 WebSocket 挂载。 */
public class JsonRpcExample {
    public static void main(String[] arguments) throws Exception {
        // 应用只选择传输与协议，协议实现通过插件 SPI 自动发现。
        boolean websocket = arguments.length > 0 && StringUtils.equalsIgnoreCase("ws", arguments[0]);
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("jsonrpc");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("jsonrpc");
        ProtocolSettings listener = incoming.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc").contextPath("/rpc");
        ProtocolSettings sender = outgoing.connector("http") //
                .protocol("jsonrpc").contextPath("/rpc");
        if (websocket) {
            listener.option("upgrade", "websocket");
            sender.option("upgrade", "websocket");
        }

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器，再注册服务提供者与消费者。
            server.start();
            client.start();
            JsonRpcDemoServiceImpl implementation = new JsonRpcDemoServiceImpl();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo") //
                    .register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher() //
                    .rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 调用方使用接口代理，普通请求与消息方法共用同一服务定义。
            JsonRpcDemoService remote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(service);
            System.out.println("echo=" + remote.echo("hello-jsonrpc"));
            System.out.println("sum=" + remote.add(7, 5));
            remote.notify("event");
            System.out.println("notification=" + implementation.getNotifications().poll(5, TimeUnit.SECONDS));
        }
    }
}
