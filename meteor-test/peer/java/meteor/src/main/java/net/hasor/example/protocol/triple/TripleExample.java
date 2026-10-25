/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.rpc.MetCaller;

/** 不依赖 proto 的 Java 接口示例；普通返回值和 Flow 均使用标准 Triple wrapper。 */
public final class TripleExample {
    private TripleExample() {
    }

    public static void main(String[] arguments) throws Exception {
        // 服务端监听，客户端只配置出站能力；默认 Hessian2 在 wrapper 内编码业务对象。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("triple");
        incoming.connector("http").bind("127.0.0.1", 0).option("httpVersion", 2) //
                .protocol("triple").contextPath("/rpc").option("streamBody", true);
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("triple");
        outgoing.connector("http").option("httpVersion", 2) //
                .protocol("triple").contextPath("/rpc").option("streamBody", true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端容器启动后再发布和订阅同一组普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").register();
            MetBindInfo<TripleDemoService> ordinary = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").register();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService").register();
            MetBindInfo<TripleFlowService> streaming = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService").register();

            // 普通 Java 多参数调用无需生成 stub，也无需自行处理 wrapper。
            var caller = client.getRsfClient(server.getListenAddress("triple"));
            TripleDemoService unary = caller.getRemote(ordinary);
            System.out.println("unary: " + unary.greet("Hello Triple ", 2));

            // 三种流式模式均使用 JDK Flow，生命周期由最终消费的 Future 表达。
            TripleFlowService flow = caller.getRemote(streaming);
            MetCaller.consume(flow.count(TripleMessage.of("response", 3)), message -> {
                System.out.println("server stream: " + message.getNumber());
            }).get(5, TimeUnit.SECONDS);
            List<TripleMessage> requests = List.of(TripleMessage.of("first", 2), TripleMessage.of("second", 3));
            TripleMessage summary = flow.collect(MetCaller.fromIterable(requests));
            System.out.println("client stream: " + summary.getNumber());
            MetCaller.consume(flow.chat(MetCaller.fromIterable(requests)), message -> {
                System.out.println("bidi stream: " + message.getText());
            }).get(5, TimeUnit.SECONDS);
        }
    }
}
