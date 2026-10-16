/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.grpc.web;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.rpc.MetCaller;

/** HTTP/1 上发布 gRPC-Web，传入 text 选择 Base64 文本传输。 */
public class GrpcWebExample {
    public static void main(String[] arguments) throws Exception {
        // 协议插件自己适配 gRPC-Web 帧，应用仍使用普通接口发布与订阅。
        boolean text = arguments.length > 0 && StringUtils.equalsIgnoreCase("text", arguments[0]);
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc-web");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc-web") //
                .contextPath("/web").option("streamBody", true);
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("grpc-web");
        outgoing.connector("http").option("httpVersion", 1).protocol("grpc-web") //
                .contextPath("/web").option("streamBody", true).option("webText", text);

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先完成容器启动，再注册服务；Json 编码无需绑定 proto。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 普通请求与服务端响应流共用同一个 HTTP 端点。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message echo = remote.echo(Message.of("hello-web", 3));
            System.out.println("echo=" + echo.getValue());
            Flow.Publisher<Message> responses = remote.list(Message.of("stream", 3));
            MetCaller.consume(responses, message -> {
                System.out.println(message.getValue() + "=" + message.getCount());
            }).get(5, TimeUnit.SECONDS);
        }
    }
}
