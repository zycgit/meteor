/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.connect;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.rpc.MetCaller;

/** 无需 proto 的 JSON 示例；运行 main 可查看普通和流式 RPC 的最终用法。 */
public final class ConnectExample {
    private ConnectExample() {
    }

    public static void main(String[] arguments) throws Exception {
        // 配置同一个 Connect 挂载点；客户端仅创建出站通道。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("connect");
        incoming.connector("http").bind("127.0.0.1", 0) //
                .protocol("connect").contextPath("/rpc").option("streamBody", true);
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("connect");
        outgoing.connector("http").option("httpVersion", 2) //
                .protocol("connect").contextPath("/rpc").option("streamBody", true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端服务容器，再发布和订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            MetBindInfo<ConnectDemoService> service = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();
            ConnectDemoService remote = client.getRsfClient(server.getListenAddress("connect")).getRemote(service);

            // 单条请求返回单条响应，与普通同步 Java 方法一致。
            ConnectMessage unary = remote.echo(ConnectMessage.of("Hello Connect", 1));
            System.out.println(unary.getText() + ": " + unary.getNumber());

            // 响应流通过统一消费入口逐条交付，Future 表示流的最终完成。
            MetCaller.consume(remote.count(ConnectMessage.of("server stream", 3)), message -> {
                System.out.println(message.getText() + ": " + message.getNumber());
            }).get(5, TimeUnit.SECONDS);

            // 请求流和双向流使用 JDK Flow.Publisher，不把流包装到 DTO 中。
            List<ConnectMessage> messages = List.of(ConnectMessage.of("first", 2), ConnectMessage.of("second", 3));
            ConnectMessage total = remote.collect(MetCaller.fromIterable(messages));
            System.out.println(total.getText() + ": " + total.getNumber());
            MetCaller.consume(remote.chat(MetCaller.fromIterable(messages)), message -> {
                System.out.println(message.getText() + ": " + message.getNumber());
            }).get(5, TimeUnit.SECONDS);
        }
    }
}
