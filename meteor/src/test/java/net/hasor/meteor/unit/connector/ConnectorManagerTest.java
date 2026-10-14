/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorManagerTest {
    @Test
    public void initializationReadsConfigurationsWithoutCreatingEndpoints() {
        // 工厂配置已装配，但管理器初始化不创建任何传输端点。
        try (EndpointFixture fixture = new EndpointFixture("a", "b")) {
            assertEquals(0, fixture.created);
            assertEquals(2, fixture.manager.configurations().size());
        }
    }

    @Test
    public void firstBindCreatesOnlyTheSelectedEndpoint() throws Exception {
        // 两个端点均已配置，首次使用只创建目标端点。
        try (EndpointFixture fixture = new EndpointFixture("a", "b")) {
            fixture.manager.bind("a").get(5, TimeUnit.SECONDS);

            // 未使用端点保持未创建，避免启动无关传输资源。
            assertEquals(1, fixture.created);
            assertNull(fixture.manager.find("b"));
        }
    }

    @Test
    public void repeatedBindReusesTheActiveListener() throws Exception {
        // 同一端点反复绑定，复用已启动的监听器。
        try (EndpointFixture fixture = new EndpointFixture("a")) {
            MetListen first = fixture.manager.bind("a").get(5, TimeUnit.SECONDS);
            MetListen reused = fixture.manager.bind("a").get(5, TimeUnit.SECONDS);

            // 监听器身份不变，连接器也只初始化一次。
            assertSame(first, reused);
            assertEquals(1, fixture.endpoints.get("a").starts);
        }
    }

    @Test
    public void concurrentFirstUseCreatesOneConnector() throws Exception {
        // 并发提交同一端点的首次绑定，检查创建互斥与结果复用。
        try (EndpointFixture fixture = new EndpointFixture("a")) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<MetListen>> calls = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    calls.add(pool.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(3, TimeUnit.SECONDS));
                        return fixture.manager.bind("a").get(5, TimeUnit.SECONDS);
                    }));
                }

                // 首批工作线程同时越过开始信号，确保竞争首次创建。
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                start.countDown();

                // 每个并发请求都得到同一监听器，工厂只调用一次。
                MetListen first = calls.get(0).get(5, TimeUnit.SECONDS);
                for (Future<MetListen> call : calls) {
                    assertSame(first, call.get(2, TimeUnit.SECONDS));
                }
                assertEquals(1, fixture.created);
            } finally {
                start.countDown();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void bindRequiresInitialization() {
        // 未初始化时绑定端点，失败应明确指向生命周期状态。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (ConnectorManager manager = new ConnectorManager(context, new TestConnectorManager.MemoryFactory())) {
            Throwable failure = manager.bind("missing").getCause();
            assertTrue(failure instanceof IllegalStateException);
        }
    }

    @Test
    public void connectRequiresInitialization() {
        // 未初始化时发起连接，不得先创建端点或连接。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (ConnectorManager manager = new ConnectorManager(context, new TestConnectorManager.MemoryFactory())) {
            Throwable failure = manager.connect(EndpointFixture.address("a")).getCause();
            assertTrue(failure instanceof IllegalStateException);
        }
    }

    @Test
    public void subscriptionRequiresInitialization() {
        // 未初始化时订阅消息，调用方直接收到状态异常。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (ConnectorManager manager = new ConnectorManager(context, new TestConnectorManager.MemoryFactory())) {
            assertThrows(IllegalStateException.class, () -> manager.subscribe((channel, id, payload) -> {
            }));
        }
    }

    @Test
    public void bindRejectsUnconfiguredEndpoint() {
        // 初始化空配置管理器，未知端点不能由绑定操作隐式创建。
        MetContext context = ConnectorTestSupport.sharedContext();
        try (ConnectorManager manager = new ConnectorManager(context, new TestConnectorManager.MemoryFactory())) {
            manager.init();
            Throwable failure = manager.bind("missing").getCause();
            assertTrue(failure instanceof IllegalArgumentException);
            assertTrue(failure.getMessage().contains("Unknown endpoint"));
        }
    }

    @Test
    public void mountedSchemesSelectTheSameEndpoint() throws Exception {
        // 同一端点挂载两个协议，保持协议名大小写无关的路由契约。
        ProtocolConfig firstProtocol = new ProtocolConfig("one", "one", "", Collections.emptyMap());
        ProtocolConfig secondProtocol = new ProtocolConfig("two", "two", "", Collections.emptyMap());
        InterAddress configAddress = EndpointFixture.address("memory");
        List<ProtocolConfig> configMounts = Arrays.asList(firstProtocol, secondProtocol);
        Map<String, String> configOptions = Collections.singletonMap("listenType", "memory");
        ConnectorConfig config = new ConnectorConfig("shared", configAddress, configOptions, configMounts, true);
        MetContext context = ConnectorTestSupport.sharedContext(config);
        try (TestConnectorManager manager = new TestConnectorManager(context)) {
            manager.prepare(config, (cfg, owner) -> new EndpointFixture.Endpoint(cfg, owner, new ArrayList<>()));
            manager.init();

            // 两种协议取得的连接都归同一端点所有。
            MetChannel first = manager.connect(EndpointFixture.address("ONE")).get(5, TimeUnit.SECONDS);
            MetChannel second = manager.connect(EndpointFixture.address("two")).get(5, TimeUnit.SECONDS);
            assertSame(first.getConnector(), second.getConnector());
            assertSame(manager.find("one"), manager.find("two"));
        }
    }
}
