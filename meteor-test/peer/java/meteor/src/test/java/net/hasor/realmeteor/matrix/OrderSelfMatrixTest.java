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
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.StringUtils;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.matrix.OrderMatrixVectors;
import net.hasor.example.matrix.OrderProtocol;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;

/** 每个协议、连接方式和业务场景单独报告；所有 Meteor 端从 bootstrap 创建并启动。 */
@ParameterizedClass(autoCloseArguments = false, name = "self/{0}/{1}", quoteTextArguments = false)
@MethodSource("configurations")
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
public class OrderSelfMatrixTest {
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

    public OrderSelfMatrixTest(OrderProtocol protocol, OrderDiscovery discovery) {
        this.protocol = protocol;
        this.discovery = discovery;
    }
    public static Collection<Object[]> configurations() {
        // 参数枚举只生成独立用例，不在一个测试方法内循环多个业务场景。
        List<String> availableProtocols = new ArrayList<>();
        for (OrderProtocol protocol : OrderProtocol.values()) {
            availableProtocols.add(protocol.protocolName());
        }
        List<String> protocols = selection("meteor.matrix.protocols", availableProtocols);
        List<OrderDiscovery> discoveries = OrderDiscovery.selected();
        List<Object[]> configurations = new ArrayList<>();
        for (OrderProtocol protocol : OrderProtocol.values()) {
            if (!selected(protocol.protocolName(), protocols)) {
                continue;
            }
            for (OrderDiscovery discovery : discoveries) {
                configurations.add(new Object[] { protocol, discovery });
            }
        }
        if (configurations.isEmpty()) {
            throw new IllegalArgumentException("The requested protocol/discovery matrix has no matching cases");
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
        if (this.discovery != OrderDiscovery.DIRECT) {
            RegistryConfiguration registry = environment.configuration(this.discovery, this.scope);
            this.incoming.addExtension(registry);
            this.outgoing.addExtension(registry);
            if (this.protocol != OrderProtocol.RSF) {
                this.incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
                this.outgoing.connector("tcp").protocol("rsf");
            }
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
            MetClient remote = this.remote(provider, consumer, subscription);

            // 保存与查询共同证明同一笔订单经过网络进入了服务端存储。
            Order order = OrderMatrixVectors.order("order");
            this.protocol.save(remote, subscription, order);
            Order found = this.protocol.find(remote, subscription, order.getId());
            assertEquals(order.getId(), found.getId());
            assertEquals(order.getDescription(), found.getDescription());
            assertArrayEquals(order.getAttachment(), found.getAttachment());
        }
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void minimumInt32IdIsPreserved() throws Exception {
        // 独立容器与业务实例只服务本次整数下界场景。
        try (MetContext provider = this.incoming.buildContext(); //
             MetContext consumer = this.outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<?> subscription = this.protocol.subscribe(consumer);
            this.assertNoCachedProvider(consumer, subscription);
            this.protocol.publish(provider, new OrderDemoServiceImpl());
            MetClient remote = this.remote(provider, consumer, subscription);

            // int32 下界同时作为入参和返回 DTO 字段，不能被转为无符号数。
            Order order = OrderMatrixVectors.order("minimum-id");
            Order saved = this.protocol.save(remote, subscription, order);
            assertEquals(Integer.MIN_VALUE, saved.getId());
        }
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void maximumInt32IdIsPreserved() throws Exception {
        // 独立容器与业务实例只服务本次整数上界场景。
        try (MetContext provider = this.incoming.buildContext(); //
             MetContext consumer = this.outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<?> subscription = this.protocol.subscribe(consumer);
            this.assertNoCachedProvider(consumer, subscription);
            this.protocol.publish(provider, new OrderDemoServiceImpl());
            MetClient remote = this.remote(provider, consumer, subscription);

            // int32 上界在不同文本、二进制编码中都保持原始数值。
            Order order = OrderMatrixVectors.order("maximum-id");
            Order saved = this.protocol.save(remote, subscription, order);
            assertEquals(Integer.MAX_VALUE, saved.getId());
        }
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void asciiEscapingCharactersArePreserved() throws Exception {
        // 公共向量只使用标准 ASCII，兼容 ONC XDR 的标准字符串约束。
        try (MetContext provider = this.incoming.buildContext(); //
             MetContext consumer = this.outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<?> subscription = this.protocol.subscribe(consumer);
            this.assertNoCachedProvider(consumer, subscription);
            this.protocol.publish(provider, new OrderDemoServiceImpl());
            MetClient remote = this.remote(provider, consumer, subscription);

            // 引号、反斜线和 XML 特殊字符按原文返回，不能泄露编码转义。
            Order order = OrderMatrixVectors.order("ascii-escaping");
            Order saved = this.protocol.save(remote, subscription, order);
            assertEquals(order.getDescription(), saved.getDescription());
        }
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void emptyAttachmentFollowsTheDeclaredEncodingDefault() throws Exception {
        // 空附件经所有编码往返，Protobuf 注解生成入口为引用值保留 presence。
        try (MetContext provider = this.incoming.buildContext(); //
             MetContext consumer = this.outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<?> subscription = this.protocol.subscribe(consumer);
            this.assertNoCachedProvider(consumer, subscription);
            this.protocol.publish(provider, new OrderDemoServiceImpl());
            MetClient remote = this.remote(provider, consumer, subscription);

            // 显式空 bytes 与未设置值不同，回读仍是零长度数组。
            Order order = OrderMatrixVectors.order("empty-attachment");
            Order saved = this.protocol.save(remote, subscription, order);
            assertArrayEquals(new byte[0], saved.getAttachment());
        }
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void everyOctetValueIsPreserved() throws Exception {
        // 所有编码消费同一份 0..255 字节向量，没有文本中转或自定义预编码。
        try (MetContext provider = this.incoming.buildContext(); //
             MetContext consumer = this.outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<?> subscription = this.protocol.subscribe(consumer);
            this.assertNoCachedProvider(consumer, subscription);
            this.protocol.publish(provider, new OrderDemoServiceImpl());
            MetClient remote = this.remote(provider, consumer, subscription);

            // 包含零字节和高位字节，验证 Base64、opaque 与二进制字段的等价性。
            Order order = OrderMatrixVectors.order("all-octets");
            Order saved = this.protocol.save(remote, subscription, order);
            assertArrayEquals(order.getAttachment(), saved.getAttachment());
        }
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void missingOrderReturnsABusinessFailureInsteadOfTimingOut() throws Exception {
        // 服务已发布且地址可用，查询失败只能来自不存在的订单业务行为。
        try (MetContext provider = this.incoming.buildContext(); //
             MetContext consumer = this.outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<?> subscription = this.protocol.subscribe(consumer);
            this.assertNoCachedProvider(consumer, subscription);
            this.protocol.publish(provider, new OrderDemoServiceImpl());
            MetClient remote = this.remote(provider, consumer, subscription);

            // 各协议可映射成自己的 Fault，但不能吞掉错误后等待请求超时。
            Exception failure = assertThrows(Exception.class, //
                    () -> this.protocol.find(remote, subscription, 404));
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                assertFalse(cause instanceof MetTimeoutException, failure.toString());
                assertFalse(cause instanceof TimeoutException, failure.toString());
            }
        }
    }

    private void assertNoCachedProvider(MetContext consumer, MetBindInfo<?> subscription) {
        // 提供者尚未发布，隔离缓存和注册空间不能出现预置业务地址。
        assertTrue(consumer.getUpdater().queryAvailableAddresses(subscription.getBindID()).isEmpty());
        assertNull(consumer.getListenAddress(this.protocol.protocolName()));
    }

    private MetClient remote(MetContext provider, MetContext consumer, MetBindInfo<?> subscription) throws Exception {
        if (this.discovery == OrderDiscovery.DIRECT) {
            return consumer.getRsfClient(provider.getListenAddress(this.protocol.protocolName()));
        }

        // 注册模式只观察自动订阅的结果，不读取提供者监听地址，也不主动拉取地址。
        RegistryDiscoveryExample.awaitDiscovery(consumer, subscription);
        var addresses = consumer.getUpdater().queryAvailableAddresses(subscription.getBindID());
        assertEquals(1, addresses.size());
        assertEquals(this.protocol.protocolName(), addresses.get(0).getSchema());
        AddressSelectionAwaiter.await(consumer, subscription, "save");
        return consumer.getRsfClient();
    }

    private static List<String> selection(String property, List<String> available) {
        String value = System.getProperty(property, "*").toLowerCase(Locale.ROOT);
        List<String> selected = new ArrayList<>();
        for (String token : value.split(",", -1)) {
            String name = token.trim();
            boolean wildcard = StringUtils.equals(name, "*") || StringUtils.equals(name, "all");
            if (!wildcard && !available.contains(name)) {
                throw new IllegalArgumentException("Unknown selection in " + property + ": " + token);
            }
            selected.add(wildcard ? "*" : name);
        }
        return selected;
    }

    private static boolean selected(String value, List<String> selection) {
        return selection.contains("*") || selection.contains(value);
    }
}
