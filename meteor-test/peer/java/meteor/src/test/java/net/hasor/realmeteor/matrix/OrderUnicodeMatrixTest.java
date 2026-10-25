/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** 第一层 Unicode 订单矩阵；ONC RPC 标准 XDR string 的 ASCII 范围由独立拒绝场景验证。 */
@RunWith(Parameterized.class)
public class OrderUnicodeMatrixTest {
    @ClassRule
    public static final TemporaryFolder          environmentData = new TemporaryFolder();
    private static      OrderRegistryEnvironment environment;

    @Rule
    public final  TemporaryFolder data = new TemporaryFolder();
    private final OrderProtocol   protocol;
    private final OrderDiscovery  discovery;

    public OrderUnicodeMatrixTest(OrderProtocol protocol, OrderDiscovery discovery) {
        this.protocol = protocol;
        this.discovery = discovery;
    }

    @Parameterized.Parameters(name = "unicode/{0}/{1}")
    public static Collection<Object[]> configurations() {
        // 复用同一份协议与发现选择规则；标准 XDR string 不属于 Unicode 正向配置。
        List<Object[]> configurations = new ArrayList<>();
        for (Object[] configuration : OrderSelfMatrixTest.configurations()) {
            if (configuration[0] != OrderProtocol.ONCRPC) {
                configurations.add(configuration);
            }
        }
        if (configurations.isEmpty()) {
            String scope = "The requested unicode protocol/discovery matrix has no matching cases";
            String reason = "standard ONC RPC XDR strings only support ASCII";
            throw new IllegalArgumentException(scope + "; " + reason);
        }
        return configurations;
    }

    @BeforeClass
    public static void createEnvironment() {
        environment = new OrderRegistryEnvironment(environmentData.getRoot().toPath());
    }

    @AfterClass
    public static void closeEnvironment() throws Exception {
        if (environment != null) {
            environment.close();
        }
    }

    @Test(timeout = 120000)
    public void unicodeDescriptionSurvivesSaveAndFind() throws Exception {
        // 每个参数独占缓存、服务分组和注册空间，两端仅安装当前业务协议。
        String scope = "unicode-" + UUID.randomUUID();
        MetConfiguration incoming = this.protocol.providerConfiguration();
        incoming.setDefaultGroup(scope).setApplicationName("unicode-provider");
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        MetConfiguration outgoing = this.protocol.consumerConfiguration();
        outgoing.setDefaultGroup(scope).setApplicationName("unicode-consumer");
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());

        try {
            // 注册模式只声明存储位置，RSF 端点只承载内部管理服务。
            if (this.discovery != OrderDiscovery.DIRECT) {
                RegistryConfiguration registry = environment.configuration(this.discovery, scope);
                incoming.addExtension(registry);
                outgoing.addExtension(registry);
                if (this.protocol != OrderProtocol.RSF) {
                    incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
                    outgoing.connector("tcp").protocol("rsf");
                }
            }
            try (MetContext provider = incoming.buildContext(); //
                 MetContext consumer = outgoing.buildContext()) {
                // 两个容器均已启动后订阅、发布；订阅前没有旧业务地址。
                provider.start();
                consumer.start();
                MetBindInfo<?> subscription = this.protocol.subscribe(consumer);
                assertTrue(consumer.getUpdater().queryAvailableAddresses(subscription.getBindID()).isEmpty());
                this.protocol.publish(provider, new OrderDemoServiceImpl());

                // 消费者只有在直连时获得监听地址，注册模式等待真实自动发现。
                MetClient remote;
                if (this.discovery == OrderDiscovery.DIRECT) {
                    remote = consumer.getRsfClient(provider.getListenAddress(this.protocol.protocolName()));
                } else {
                    RegistryDiscoveryExample.awaitDiscovery(consumer, subscription);
                    AddressSelectionAwaiter.await(consumer, subscription, "save");
                    remote = consumer.getRsfClient();
                }

                // 含多种文字和补充平面字符，参数、存储和返回值保持同一份文本。
                Order request = OrderMatrixVectors.order("unicode-order");
                this.protocol.save(remote, subscription, request);
                Order actual = this.protocol.find(remote, subscription, request.getId());
                assertEquals(request.getId(), actual.getId());
                assertEquals(request.getDescription(), actual.getDescription());
                assertArrayEquals(request.getAttachment(), actual.getAttachment());
            }
        } finally {
            // 所有应用已退出，再释放当前用例独占的 JDBC 内存数据库。
            environment.release(scope);
        }
    }
}
