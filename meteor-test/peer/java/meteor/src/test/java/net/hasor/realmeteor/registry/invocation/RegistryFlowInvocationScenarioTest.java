/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.invocation;
import java.nio.file.Path;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.example.direct.stream.CatalogItem;
import net.hasor.example.direct.stream.CatalogStreamService;
import net.hasor.example.direct.stream.StreamRpcExample;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Flow 调用通过三种真实注册存储自动发现，同一普通接口分别使用 JSON 和 Protobuf。 */
@RunWith(Parameterized.class)
public class RegistryFlowInvocationScenarioTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;
    private final String format;

    public RegistryFlowInvocationScenarioTest(String backend, String format) {
        this.backend = backend;
        this.format = format;
    }

    @Parameterized.Parameters(name = "flow/{0}/{1}")
    public static Object[][] combinations() {
        return new Object[][] { // 每一行是独立的注册发现与协议编码组合。
                { "jdbc", "Json" }, //
                { "jdbc", "Protobuf" }, //
                { "zookeeper", "Json" }, //
                { "zookeeper", "Protobuf" }, //
                { "nacos", "Json" }, //
                { "nacos", "Protobuf" }, //
        };
    }

    @Test(timeout = 180000)
    public void discoveredResponseStreamDeliversOnlyRequestedItemsInOrder() throws Exception {
        // 两个应用只配置协议与同一个注册库，消费端不绑定端口或指定远端地址。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "flow-provider", true);
            MetConfiguration outgoing = this.configuration(registry, "flow-consumer", false);
            try (MetContext provider = incoming.buildContext(); //
                 MetContext consumer = outgoing.buildContext()) {
                provider.start();
                consumer.start();
                StreamRpcExample.publish(provider, this.format);
                MetBindInfo<CatalogStreamService> binding = StreamRpcExample.subscribe(consumer, this.format);
                AddressSelectionAwaiter.await(consumer, binding, "list");
                CatalogStreamService remote = consumer.getRsfClient().getRemote(binding);

                // 先订阅但不声明需求，使用队列观察本次流的交付，而非观察网络内部缓冲。
                LinkedBlockingQueue<CatalogItem> received = new LinkedBlockingQueue<>();
                BasicFuture<Flow.Subscription> subscribed = new BasicFuture<>();
                BasicFuture<Void> completed = new BasicFuture<>();
                remote.list(this.item("registered-catalog", 3)).subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        subscribed.completed(subscription);
                    }

                    @Override
                    public void onNext(CatalogItem item) {
                        received.add(item);
                    }

                    @Override
                    public void onError(Throwable failure) {
                        completed.failed(failure);
                    }

                    @Override
                    public void onComplete() {
                        completed.completed(null);
                    }
                });

                Flow.Subscription demand = subscribed.get(5, TimeUnit.SECONDS);
                try {
                    CatalogItem unsolicited = received.poll(100, TimeUnit.MILLISECONDS);
                    assertNull("No item may be delivered without demand", unsolicited);

                    // 首次只请求一条；交付后继续等待下一次需求声明。
                    demand.request(1);
                    CatalogItem first = received.poll(5, TimeUnit.SECONDS);
                    assertNotNull("First requested item was not delivered", first);
                    assertEquals("registered-catalog", first.getName());
                    assertEquals(0, first.getCount());
                    CatalogItem extra = received.poll(100, TimeUnit.MILLISECONDS);
                    assertNull("One request must not deliver two items", extra);

                    // 再请求剩余两条，完成信号与有序数据共同证明本次响应流正常结束。
                    demand.request(2);
                    for (int index = 1; index < 3; index++) {
                        CatalogItem item = received.poll(5, TimeUnit.SECONDS);
                        assertNotNull("Requested item was not delivered", item);
                        assertEquals("registered-catalog", item.getName());
                        assertEquals(index, item.getCount());
                    }
                    completed.get(5, TimeUnit.SECONDS);
                    assertTrue("Completed stream retained unexpected items", received.isEmpty());
                } finally {
                    demand.cancel();
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void cancellingDiscoveredResponseStreamKeepsSubsequentUnaryCallUsable() throws Exception {
        // 独占的注册环境中发布相同业务接口，先让正式地址选择快照完成自动发现。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "cancel-provider", true);
            MetConfiguration outgoing = this.configuration(registry, "cancel-consumer", false);
            try (MetContext provider = incoming.buildContext(); //
                 MetContext consumer = outgoing.buildContext()) {
                provider.start();
                consumer.start();
                StreamRpcExample.publish(provider, this.format);
                MetBindInfo<CatalogStreamService> binding = StreamRpcExample.subscribe(consumer, this.format);
                AddressSelectionAwaiter.await(consumer, binding, "list");
                CatalogStreamService remote = consumer.getRsfClient().getRemote(binding);

                // 只消费响应流的第一条，保留未消费消息后显式取消当前订阅。
                LinkedBlockingQueue<CatalogItem> received = new LinkedBlockingQueue<>();
                BasicFuture<Flow.Subscription> subscribed = new BasicFuture<>();
                remote.list(this.item("cancelled-catalog", 512)).subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        subscribed.completed(subscription);
                    }

                    @Override
                    public void onNext(CatalogItem item) {
                        received.add(item);
                    }

                    @Override
                    public void onError(Throwable failure) {
                        subscribed.failed(failure);
                    }

                    @Override
                    public void onComplete() {
                    }
                });

                Flow.Subscription demand = subscribed.get(5, TimeUnit.SECONDS);
                try {
                    demand.request(1);
                    CatalogItem first = received.poll(5, TimeUnit.SECONDS);
                    assertNotNull("First item was not delivered before cancellation", first);
                    assertEquals(0, first.getCount());
                } finally {
                    demand.cancel();
                }

                // 不推断远端内部释放时刻，仅验证取消一个流不影响同服务后续普通网络调用。
                CatalogItem actual = remote.echo(this.item("after-cancellation", 7));
                assertEquals("after-cancellation", actual.getName());
                assertEquals(7, actual.getCount());
                CatalogItem afterCancellation = received.poll(100, TimeUnit.MILLISECONDS);
                assertNull("Cancelled subscriber received another item", afterCancellation);
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String application, boolean provider) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc").setDefaultTimeout(10000);
        configuration.setApplicationName(application);
        configuration.setDataHome(this.data.newFolder(application).toPath());
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf");

        // 内部管理出口使用 RSF；只有提供者的 HTTP/2 端点额外开放监听。
        var http = configuration.connector("http").option("httpVersion", 2);
        if (provider) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("grpc").addressScheme("http").contextPath("/").option("streamBody", true);
        return configuration;
    }

    private CatalogItem item(String name, int count) {
        CatalogItem item = new CatalogItem();
        item.setName(name);
        item.setCount(count);
        return item;
    }
}
