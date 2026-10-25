/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc.python;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.matrix.OrderMatrixVectors;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import net.hasor.realmeteor.environment.container.ContainerProcess;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Python 标准库 JSON-RPC 的显式注册适配；不宣称外部对端原生理解 Meteor 注册模型。 */
@RunWith(Parameterized.class)
public class PythonJsonRpcRegistryAdapterScenarioTest {
    @Parameterized.Parameters(name = "jsonrpc/python/adapter/{0}")
    public static Collection<Object[]> backends() {
        return List.of(new Object[] { "jdbc" }, new Object[] { "zookeeper" }, new Object[] { "nacos" });
    }

    @Rule
    public final  TemporaryFolder data = new TemporaryFolder();
    private final String          backend;

    public PythonJsonRpcRegistryAdapterScenarioTest(String backend) {
        this.backend = backend;
    }

    @Test(timeout = 180000)
    public void pythonConsumerDiscoversMeteorOrderProvider() throws Exception {
        // 提供者仅配置自己的注册库，启动后才通过公开 API 发布原有订单接口。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration incoming = this.configuration(registry, "meteor-jsonrpc-provider");
            incoming.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                    .protocol("jsonrpc").addressScheme("http").contextPath("/jsonrpc");
            try (MetContext provider = incoming.buildContext()) {
                provider.start();

                // 观察实际业务入口与返回，成功时不输出日志，不改变原订单实现。
                long started = System.nanoTime();
                AtomicInteger savesEntered = new AtomicInteger();
                AtomicInteger savesReturned = new AtomicInteger();
                AtomicInteger findsEntered = new AtomicInteger();
                AtomicInteger findsReturned = new AtomicInteger();
                Queue<String> events = new ConcurrentLinkedQueue<>();
                OrderDemoServiceImpl implementation = new OrderDemoServiceImpl();
                OrderDemoService observed = new OrderDemoService() {
                    @Override
                    public Order save(Order order) {
                        savesEntered.incrementAndGet();
                        long entered = System.nanoTime() - started;
                        events.add("save entered nanos=" + entered + " thread=" + Thread.currentThread().getName());
                        try {
                            Order result = implementation.save(order);
                            savesReturned.incrementAndGet();
                            long returned = System.nanoTime() - started;
                            events.add("save returned nanos=" + returned);
                            return result;
                        } catch (RuntimeException | Error failure) {
                            events.add("save failed: " + failure);
                            throw failure;
                        }
                    }

                    @Override
                    public Order find(OrderQuery query) {
                        findsEntered.incrementAndGet();
                        long entered = System.nanoTime() - started;
                        events.add("find entered nanos=" + entered + " thread=" + Thread.currentThread().getName());
                        try {
                            Order result = implementation.find(query);
                            findsReturned.incrementAndGet();
                            long returned = System.nanoTime() - started;
                            events.add("find returned nanos=" + returned);
                            return result;
                        } catch (RuntimeException | Error failure) {
                            events.add("find failed: " + failure);
                            throw failure;
                        }
                    }
                };
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, observed) //
                        .name("orders").aliasName("jsonrpc", "orders").protocol("jsonrpc").register();

                // 存储订阅解析出实际端点；不读取提供者监听地址，不从 Java 转发业务请求。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "jsonrpc")) {
                    URI endpoint = resolver.awaitCount(1).get(0);
                    assertEquals("http", endpoint.getScheme());
                    assertEquals("/jsonrpc", endpoint.getPath());

                    // Python 标准库直接保存和查询共享向量，并在进程内核对 DTO 与 bytes。
                    String target = endpoint.toString();
                    try (PythonJsonRpcOrderPeer consumer = PythonJsonRpcOrderPeer.consumer(target, "order")) {
                        try {
                            consumer.requireSuccess();
                        } catch (Exception | AssertionError failure) {
                            // 在关闭提供者前保存业务进度和所有线程，诊断异常不能替换原失败。
                            try {
                                System.err.println("JSON-RPC adapter failure: backend=" + this.backend);
                                System.err.println("Discovered endpoint=" + endpoint);
                                System.err.println("Provider listener=" + provider.getListenAddress("jsonrpc"));
                                System.err.println("Service=" + serviceId);
                                System.err.println("save entered=" + savesEntered + " returned=" + savesReturned);
                                System.err.println("find entered=" + findsEntered + " returned=" + findsReturned);
                                events.forEach(event -> System.err.println("Business event: " + event));
                                dumpThreads();
                            } catch (RuntimeException | Error diagnosticFailure) {
                                failure.addSuppressed(diagnosticFailure);
                            }
                            throw failure;
                        }
                    }
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversPythonOrderProvider() throws Exception {
        // Meteor 只持有出站挂载和注册配置，接口订阅没有固定业务地址。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, "meteor-jsonrpc-consumer");
            outgoing.connector("http").protocol("jsonrpc").addressScheme("http").contextPath("/jsonrpc");
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher() //
                        .rsfService(OrderDemoService.class).name("orders") //
                        .aliasName("jsonrpc", "orders").protocol("jsonrpc").register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 独立进程确认监听后，由显式适配登记其真实端点、协议与进程信息。
                try (PythonJsonRpcOrderPeer provider = PythonJsonRpcOrderPeer.provider(); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    int port = provider.awaitPort();
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress("http", provider.host(), port, unit);
                    registrar.register(service, provider.processId(), "python", "jsonrpc", "http", address, "/jsonrpc");
                    AddressSelectionAwaiter.await(consumer, service, "save");

                    // 自动地址本选择 Python 服务；Java 代理执行真正的保存和查询网络调用。
                    Order expected = OrderMatrixVectors.order("unicode-order");
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    Order saved = orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    Order actual = orders.find(query);
                    assertEquals(expected.getId(), saved.getId());
                    assertEquals(expected.getId(), actual.getId());
                    assertEquals(expected.getDescription(), actual.getDescription());
                    assertArrayEquals(expected.getAttachment(), actual.getAttachment());
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String application) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        configuration.setApplicationName(application);
        configuration.setDataHome(this.data.newFolder(application).toPath());
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 内部管理出口不监听，不参与业务发现。
        return configuration;
    }

    private static void dumpThreads() {
        // 网络与业务线程优先输出，其余线程也保留，避免隐藏持锁或调度根因。
        Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        List<Thread> threads = new ArrayList<>(stacks.keySet());
        Comparator<Thread> order = Comparator.comparing((Thread thread) -> {
            String name = thread.getName();
            boolean transport = name.startsWith("Meteor(") || name.contains("Neta") || name.contains("neta");
            boolean invocation = name.startsWith("RSF-");
            return transport || invocation ? 0 : 1;
        });
        threads.sort(order.thenComparing(Thread::getName));
        for (Thread thread : threads) {
            String identity = "Thread \"" + thread.getName() + "\" id=" + thread.getId();
            System.err.println(identity + " state=" + thread.getState());
            for (StackTraceElement frame : stacks.get(thread)) {
                System.err.println("    at " + frame);
            }
        }
    }
}
