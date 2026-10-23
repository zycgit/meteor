/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct.stream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.direct.stream.CatalogItem;
import net.hasor.example.direct.stream.CatalogStreamService;
import net.hasor.example.direct.stream.StreamRpcExample;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** 同一组公开 Java 接口分别用 JSON、Protobuf 演示四种调用形态。 */
@RunWith(Parameterized.class)
public class StreamScenarioTest {
    private final String format;

    public StreamScenarioTest(String format) {
        this.format = format;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> formats() {
        return List.of(new Object[] { "Json" }, new Object[] { "Protobuf" });
    }

    @Test(timeout = 20000)
    public void unaryUsesTheSamePojoContractWithTheSelectedEncoding() throws Exception {
        // 配置复用点对点示例；服务在双方容器启动之后发布和订阅。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("grpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            StreamRpcExample.publish(server, this.format);
            MetBindInfo<CatalogStreamService> binding = StreamRpcExample.subscribe(client, this.format);
            CatalogStreamService remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(binding);

            // 普通响应仍使用同一个 POJO，编码差异只由服务绑定决定。
            CatalogItem result = remote.echo(this.item("unary", 7));
            assertEquals("unary", result.getName());
            assertEquals(7, result.getCount());
        }
    }

    @Test(timeout = 20000)
    public void responseStreamRequestsOneItemAtATime() throws Exception {
        // 普通请求发起一条响应流，不为每条响应创建新的 RPC。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("grpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            StreamRpcExample.publish(server, this.format);
            MetBindInfo<CatalogStreamService> binding = StreamRpcExample.subscribe(client, this.format);
            CatalogStreamService remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(binding);

            // request(1) 只声明下一条消息的接收能力，不代表流的总消息数。
            List<CatalogItem> items = new ArrayList<>();
            BasicFuture<Void> completion = new BasicFuture<>();
            remote.list(this.item("catalog", 3)).subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                public void onSubscribe(Flow.Subscription subscription) {
                    this.subscription = subscription;
                    this.subscription.request(1);
                }

                public void onNext(CatalogItem item) {
                    items.add(item);
                    this.subscription.request(1);
                }

                public void onError(Throwable error) {
                    completion.failed(error);
                }

                public void onComplete() {
                    completion.completed(null);
                }
            });

            // 完成信号到达之后再关闭上下文，逐项核对接收顺序。
            completion.get(5, TimeUnit.SECONDS);
            assertEquals(3, items.size());
            for (int index = 0; index < items.size(); index++) {
                assertEquals("catalog", items.get(index).getName());
                assertEquals(index, items.get(index).getCount());
            }
        }
    }

    @Test(timeout = 20000)
    public void requestStreamReturnsTheAccumulatedResult() throws Exception {
        // 客户端使用 Publisher 提交多条消息，服务端消费完后返回一个结果。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("grpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            StreamRpcExample.publish(server, this.format);
            MetBindInfo<CatalogStreamService> binding = StreamRpcExample.subscribe(client, this.format);
            CatalogStreamService remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(binding);

            // 普通集合通过公开工具转为 Publisher，业务实现负责消费请求流。
            List<CatalogItem> input = new ArrayList<>();
            input.add(this.item("first", 2));
            input.add(this.item("second", 5));
            CatalogItem result = remote.sum(MetCaller.fromIterable(input));
            assertEquals("total", result.getName());
            assertEquals(7, result.getCount());
        }
    }

    @Test(timeout = 20000)
    public void bidirectionalStreamPreservesTheInputSequence() throws Exception {
        // 同一条 RPC 内请求和响应都持续传递消息，仍通过点对点 bootstrap 组装。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("grpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            StreamRpcExample.publish(server, this.format);
            MetBindInfo<CatalogStreamService> binding = StreamRpcExample.subscribe(client, this.format);
            CatalogStreamService remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(binding);

            // 输入顺序用于核对回传位置，逐项构建并保留传递顺序。
            List<CatalogItem> input = new ArrayList<>();
            input.add(this.item("first", 1));
            input.add(this.item("second", 2));
            List<CatalogItem> output = new ArrayList<>();
            var source = MetCaller.fromIterable(input);
            MetCaller.consume(remote.exchange(source), output::add).get(5, TimeUnit.SECONDS);

            // 流正常结束后，响应顺序和对象字段与输入对应。
            assertEquals(input.size(), output.size());
            for (int index = 0; index < input.size(); index++) {
                assertEquals(input.get(index).getName(), output.get(index).getName());
                assertEquals(input.get(index).getCount(), output.get(index).getCount());
            }
        }
    }

    private CatalogItem item(String name, int count) {
        CatalogItem item = new CatalogItem();
        item.setName(name);
        item.setCount(count);
        return item;
    }
}
