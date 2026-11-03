/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.util.*;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.matrix.OrderMatrixVectors;
import net.hasor.example.matrix.OrderProtocol;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;

/** 注册场景只通过自动订阅地址调用；普通直连订单由标准能力矩阵执行。 */
@ParameterizedClass(autoCloseArguments = false, name = "self/{0}/{1}", quoteTextArguments = false)
@MethodSource("registryConfigurations")
@NxnRpc(name = "rsf", version = "1", cases = "self/rsf/*")
@NxnRpc(name = "grpc", cases = "self/grpc/*")
@NxnRpc(name = "grpc-web", cases = "self/grpc-web/*")
@NxnRpc(name = "hprose", cases = "self/hprose/*")
@NxnRpc(name = "jsonrpc", version = "2.0", cases = "self/jsonrpc/*")
@NxnRpc(name = "xmlrpc", cases = "self/xmlrpc/*")
@NxnRpc(name = "soap", version = "1.1", cases = "self/soap/*")
@NxnRpc(name = "hessian", version = "2", cases = "self/hessian/*")
@NxnRpc(name = "connect", version = "1", cases = "self/connect/*")
@NxnRpc(name = "triple", cases = "self/triple/*")
@NxnRpc(name = "dubbo", cases = "self/dubbo/*")
@NxnRpc(name = "thrift", cases = "self/thrift/*")
@NxnRpc(name = "oncrpc", version = "2", cases = "self/oncrpc/*")
public class OrderRegistryMatrixTest {
    @TempDir
    public static Path environmentData;
    private static      OrderRegistryEnvironment environment;

    @TempDir
    public Path data;
    private final OrderProtocol    protocol;
    private final OrderDiscovery   discovery;
    private       String           scope;
    private       MetConfiguration incoming;
    private       MetConfiguration outgoing;

    public OrderRegistryMatrixTest(OrderProtocol protocol, OrderDiscovery discovery) {
        this.protocol = protocol;
        this.discovery = discovery;
    }
    public static Collection<Object[]> registryConfigurations() {
        // 共享协议与发现筛选规则，排除已交给标准能力矩阵的普通直连场景。
        List<Object[]> configurations = new ArrayList<>();
        for (Object[] configuration : OrderSelfMatrixTest.configurations()) {
            if (configuration[1] != OrderDiscovery.DIRECT) {
                configurations.add(configuration);
            }
        }
        if (configurations.isEmpty()) {
            throw new IllegalArgumentException("The requested registry matrix has no matching cases");
        }
        return configurations;
    }

    @BeforeAll
    public static void createEnvironment() {
        environment = new OrderRegistryEnvironment(environmentData);
    }

    @AfterAll
    public static void closeEnvironment() throws Exception {
        if (environment != null) {
            environment.close();
        }
    }

    @BeforeEach
    public void configureApplications() throws Exception {
        // 每个方法拥有独立服务身份、数据目录和注册空间，防止从上一例缓存发现地址。
        this.scope = UUID.randomUUID().toString();
        this.incoming = this.protocol.providerConfiguration();
        this.incoming.setDefaultGroup(this.scope);
        this.incoming.setApplicationName("matrix-provider");
        this.incoming.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        this.outgoing = this.protocol.consumerConfiguration();
        this.outgoing.setDefaultGroup(this.scope);
        this.outgoing.setApplicationName("matrix-consumer");
        this.outgoing.setDataHome(Files.createDirectory(this.data.resolve("consumer")));

        // 注册模式只配置存储位置；内部管理使用独立 RSF 端点，业务服务仍限定当前协议。
        RegistryConfiguration registry = environment.configuration(this.discovery, this.scope);
        this.incoming.addExtension(registry);
        this.outgoing.addExtension(registry);
        if (this.protocol != OrderProtocol.RSF) {
            this.incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
            this.outgoing.connector("tcp").protocol("rsf");
        }
    }

    @AfterEach
    public void releaseDatabase() throws Exception {
        environment.release(this.scope);
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void savedOrderCanBeFoundThroughTheSelectedProtocol() throws Exception {
        // 先启动全部容器，再订阅与发布；两端从公开 API 构建真实网络链路。
        try (MetContext provider = this.incoming.buildContext(); //
             MetContext consumer = this.outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<?> subscription = this.protocol.subscribe(consumer);
            this.assertNoCachedProvider(consumer, subscription);
            MetBindInfo<?> publication = this.protocol.publish(provider, new OrderDemoServiceImpl());
            assertEquals(publication.getBindID(), subscription.getBindID());
            MetClient remote = this.remote(consumer, subscription);

            // 保存与查询共同证明同一笔订单经过网络进入了服务端存储。
            Order order = OrderMatrixVectors.order("order");
            this.protocol.save(remote, subscription, order);
            Order found = this.protocol.find(remote, subscription, order.getId());
            assertEquals(order.getId(), found.getId());
            assertEquals(order.getDescription(), found.getDescription());
            assertArrayEquals(order.getAttachment(), found.getAttachment());
        }
    }

    private void assertNoCachedProvider(MetContext consumer, MetBindInfo<?> subscription) {
        // 提供者尚未发布，隔离缓存和注册空间不能出现预置业务地址。
        assertTrue(consumer.getUpdater().queryAvailableAddresses(subscription.getBindID()).isEmpty());
        assertNull(consumer.getListenAddress(this.protocol.protocolName()));
    }

    private MetClient remote(MetContext consumer, MetBindInfo<?> subscription) throws Exception {
        // 注册模式只观察自动订阅的结果，不读取提供者监听地址，也不主动拉取地址。
        RegistryDiscoveryExample.awaitDiscovery(consumer, subscription);
        var addresses = consumer.getUpdater().queryAvailableAddresses(subscription.getBindID());
        assertEquals(1, addresses.size());
        assertEquals(this.protocol.protocolName(), addresses.get(0).getSchema());
        AddressSelectionAwaiter.await(consumer, subscription, "save");
        return consumer.getRsfClient();
    }
}
