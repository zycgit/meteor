/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.connector.protocol.rsf;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.scenario.connector.protocol.rsf.example.EchoService;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 两个独立应用经公开入口执行 Meteor 到 Meteor 的真实 RSF 调用。 */
public class RsfInvocationTest {
    @Test
    public void concurrentProxyCallsKeepTheirOwnResults() throws Exception {
        // 服务端监听临时 TCP 端口，客户端只配置出站协议。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 实现在服务端发布，客户端只注册接口并以服务端地址取得代理。
            server.publisher() //
                    .rsfService(EchoService.class, value -> value) //
                    .serialize("Java").register();
            MetBindInfo<EchoService> service = client.publisher() //
                    .rsfService(EchoService.class) //
                    .serialize("Java").register();
            server.start();
            client.start();
            EchoService remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);

            // 并发调用同一个远程代理，每个返回值必须属于发起它的请求。
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                List<Future<String>> results = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    String value = "value-" + i;
                    results.add(workers.submit(() -> remote.echo(value)));
                }
                for (int i = 0; i < results.size(); i++) {
                    String returned = results.get(i).get(5, TimeUnit.SECONDS);
                    assertEquals("value-" + i, returned);
                }
            } finally {
                workers.shutdownNow();
            }
        }
    }
}
