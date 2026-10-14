/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.container;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.AddressBucket;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.container.MetContainer;
import net.hasor.meteor.unit.container.ContainerFixtures.Echo;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetContainerAddressTest {
    private final AddressPool  addresses = new AddressPool("unit", 1000);
    private final MetSettings  settings  = ContainerFixtures.settings();
    private final MetContainer container = new MetContainer(this.addresses, this.settings);
    private final MetPublisher publisher = this.container.createPublisher();

    @Test
    public void registrationBecomesVisibleAfterAddressesAreReady() throws Exception {
        // 在地址池完成登记前阻塞发布线程，观察容器对外可见性。
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AddressPool addresses = new AddressPool() {
            @Override
            public void registerBucket(AddressBucket bucket) {
                // 明确发出进入地址准备阶段的信号，随后等待测试线程放行。
                preparing.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                super.registerBucket(bucket);
            }
        };
        MetContainer local = new MetContainer(addresses, this.settings);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<MetBindInfo<Echo>> publishing = worker.submit(() -> {
                var serviceBuilder = local.createPublisher().rsfService(Echo.class);
                serviceBuilder.bindAddress("rsf://localhost:2181/default");
                return serviceBuilder.register();
            });
            try {
                // 地址尚未准备好时，类型查询和列表查询都不能看到服务。
                assertTrue(preparing.await(5, TimeUnit.SECONDS));
                assertNull(local.getRsfBindInfo(Echo.class));
                assertTrue(local.getServiceIDs().isEmpty());
            } finally {
                release.countDown();
            }

            // 放行地址登记后，服务和可选择地址同时可用。
            MetBindInfo<Echo> service = publishing.get(5, TimeUnit.SECONDS);
            assertNotNull(local.getRsfBindInfo(service.getBindID()));
            assertNotNull(addresses.nextAddress(service.getBindID(), "echo", null));
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void registrationPreservesAddressesDiscoveredBeforeServiceDeclaration() throws Exception {
        // 服务声明之前，地址发现已写入动态地址与方法路由。
        String id = "[default]" + Echo.class.getName() + "-1.0";
        InterAddress discovered = new InterAddress("rsf://localhost:2181/default");
        InterAddress fixed = new InterAddress("rsf://localhost:2182/default");
        this.addresses.appendAddress(id, discovered);
        String methodRoute = "<route><return value='addresses'/></route>";
        this.addresses.updateMethodRoute(id, methodRoute);

        // 登记静态地址时合并旧地址本，不能丢失动态地址和方法规则。
        MetBindInfo<Echo> service = this.publisher.rsfService(Echo.class).bindAddress(fixed).register();
        assertEquals(id, service.getBindID());
        Set<InterAddress> actual = new HashSet<>(this.addresses.queryAllAddresses(id));
        assertEquals(Set.of(discovered, fixed), actual);
        assertEquals(List.of(fixed), this.addresses.getBucket(id).getStaticAddresses());
        assertEquals(methodRoute, this.addresses.methodRoute(id));
    }

    @Test
    public void uriAddressListRegistersAllAddresses() throws Exception {
        // URI 可变参数中的每个地址都进入容器使用的地址池。
        URI first = new URI("rsf://127.0.0.1:2101/unit");
        URI second = new URI("rsf://127.0.0.1:2102/unit");
        var serviceBuilder = this.publisher.rsfService(Echo.class);
        serviceBuilder.bindAddress(first, second);
        MetBindInfo<Echo> service = serviceBuilder.register();
        Set<InterAddress> expected = Set.of(new InterAddress(first), new InterAddress(second));
        Set<InterAddress> actual = new HashSet<>(this.addresses.queryAllAddresses(service.getBindID()));
        assertEquals(expected, actual);
    }

    @Test
    public void hostAddressRegistrationUsesTheConfiguredUnit() throws Exception {
        // 主机端口重载从容器配置取得 unit 名称。
        var builder = this.publisher.rsfService(Echo.class);
        builder.bindAddress("127.0.0.1", 2101);
        MetBindInfo<Echo> service = builder.register();
        List<InterAddress> addresses = this.addresses.queryAllAddresses(service.getBindID());
        InterAddress expected = new InterAddress("rsf://127.0.0.1:2101/unit");
        assertEquals(List.of(expected), addresses);
    }

    @Test
    public void stringAddressListRegistersAllAddresses() throws Exception {
        // 字符串可变参数中的每个地址都进入容器使用的地址池。
        var builder = this.publisher.rsfService(Echo.class);
        builder.bindAddress("rsf://127.0.0.1:2102/unit", "rsf://127.0.0.1:2103/unit");
        MetBindInfo<Echo> service = builder.register();
        InterAddress first = new InterAddress("rsf://127.0.0.1:2102/unit");
        InterAddress second = new InterAddress("rsf://127.0.0.1:2103/unit");
        Set<InterAddress> actual = new HashSet<>(this.addresses.queryAllAddresses(service.getBindID()));
        assertEquals(Set.of(first, second), actual);
    }

    @Test
    public void addressOverloadsAccumulateWithinOneDeclaration() throws Exception {
        // 同一个声明先使用主机端口重载，再追加字符串列表。
        var builder = this.publisher.rsfService(Echo.class);
        builder.bindAddress("127.0.0.1", 2101);
        builder.bindAddress("rsf://127.0.0.1:2102/unit", "rsf://127.0.0.1:2103/unit");
        MetBindInfo<Echo> service = builder.register();

        // 后一次配置不能覆盖前一次已经加入的地址。
        InterAddress hostAddress = new InterAddress("rsf://127.0.0.1:2101/unit");
        InterAddress firstString = new InterAddress("rsf://127.0.0.1:2102/unit");
        InterAddress secondString = new InterAddress("rsf://127.0.0.1:2103/unit");
        Set<InterAddress> expected = Set.of(hostAddress, firstString, secondString);
        Set<InterAddress> actual = new HashSet<>(this.addresses.queryAllAddresses(service.getBindID()));
        assertEquals(expected, actual);
    }

    @Test
    public void publishedServiceRoutesReachTheAddressPoolBeforeSelection() {
        // 服务声明携带两个地址，并以服务路由选择其中一个端口。
        InterAddress first = new InterAddress("rsf", "127.0.0.1", 2101, "unit");
        InterAddress second = new InterAddress("rsf", "127.0.0.1", 2102, "unit");
        String route = """
                <route>
                    <filter name="selected" from="addresses" item="a" test="a.port == 2102"/>
                    <return value="selected"/>
                </route>
                """;
        var builder = this.publisher.rsfService(Echo.class);
        builder.bindAddress(first, second);
        builder.updateServiceRoute(route);
        MetBindInfo<Echo> service = builder.register();

        // 第一次选址之前，路由文本和执行结果都已准备完成。
        assertEquals(route, this.addresses.serviceRoute(service.getBindID()));
        InterAddress selected = this.addresses.nextAddress(service.getBindID(), "echo", new Object[0]);
        assertEquals(second, selected);
    }

    @Test
    public void replacementDoesNotInheritRecoveredServiceRoute() {
        // 旧服务的规则只允许 2102 端口，回收时应随地址本一并清理。
        InterAddress first = new InterAddress("rsf", "127.0.0.1", 2101, "unit");
        InterAddress second = new InterAddress("rsf", "127.0.0.1", 2102, "unit");
        String route = """
                <route>
                    <filter name="selected" from="addresses" item="a" test="a.port == 2102"/>
                    <return value="selected"/>
                </route>
                """;
        var builder = this.publisher.rsfService(Echo.class);
        builder.bindAddress(first, second);
        builder.updateServiceRoute(route);
        MetBindInfo<Echo> service = builder.register();
        assertTrue(this.container.recoverService(service.getBindID()));

        // 同 ID 新服务只绑定 2101，不能继承旧规则而变得不可选址。
        MetBindInfo<Echo> replacement = this.publisher.rsfService(Echo.class).bindAddress(first).register();
        assertEquals(service.getBindID(), replacement.getBindID());
        assertNull(this.addresses.serviceRoute(replacement.getBindID()));
        InterAddress selected = this.addresses.nextAddress(replacement.getBindID(), "echo", new Object[0]);
        assertEquals(first, selected);
    }
}
