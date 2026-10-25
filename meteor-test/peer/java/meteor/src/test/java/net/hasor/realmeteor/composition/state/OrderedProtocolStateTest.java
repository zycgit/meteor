/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.composition.state;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.hasor.cobble.StringUtils;
import net.hasor.example.composition.state.OrderCompositionConfiguration;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.matrix.OrderProtocol;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.matrix.OrderDiscovery;
import net.hasor.realmeteor.matrix.OrderRegistryEnvironment;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** 每个有序协议组合独立保存、回读同一订单；消费者只在直连模式接收业务地址。 */
@RunWith(Parameterized.class)
public class OrderedProtocolStateTest {
    @ClassRule
    public static final TemporaryFolder          serverData = new TemporaryFolder();
    private static      OrderRegistryEnvironment registries;
    @Rule
    public final        TemporaryFolder          data       = new TemporaryFolder();
    private final       OrderProtocol            write;
    private final       OrderProtocol            read;
    private final       OrderDiscovery           discovery;

    public OrderedProtocolStateTest(OrderProtocol write, OrderProtocol read, OrderDiscovery discovery) {
        this.write = write;
        this.read = read;
        this.discovery = discovery;
    }

    @Parameterized.Parameters(name = "write={0}, read={1}, discovery={2}")
    public static Collection<Object[]> cases() {
        List<OrderProtocol> writes = selectedProtocols("meteor.matrix.writeProtocols");
        List<OrderProtocol> reads = selectedProtocols("meteor.matrix.readProtocols");
        List<OrderDiscovery> discoveries = OrderDiscovery.selected();
        List<Object[]> cases = new ArrayList<>();

        // 循环只枚举 JUnit 实例；每个实例恰好执行一个写入和跨协议回读场景。
        for (OrderProtocol write : writes) {
            for (OrderProtocol read : reads) {
                if (write == read) {
                    continue;
                }
                for (OrderDiscovery discovery : discoveries) {
                    cases.add(new Object[] { write, read, discovery });
                }
            }
        }
        if (cases.isEmpty()) {
            String scope = "The requested ordered protocol matrix has no matching cases";
            throw new IllegalArgumentException(scope + "; write and read protocols must differ");
        }
        return cases;
    }

    @BeforeClass
    public static void createRegistryEnvironment() throws Exception {
        // 外部服务由整个测试类持有，各用例仍拥有独立命名空间、数据库和应用上下文。
        registries = new OrderRegistryEnvironment(serverData.newFolder("registries").toPath());
    }

    @AfterClass
    public static void closeRegistryEnvironment() throws Exception {
        if (registries != null) {
            registries.close();
        }
    }

    @Test(timeout = 180000)
    public void orderSavedThroughOneProtocolIsVisibleThroughAnother() throws Exception {
        // 当前参数只开启两个业务协议；HTTP 共享一个监听端口，IDL 桥共用订单存储。
        boolean discovered = this.discovery != OrderDiscovery.DIRECT;
        String scope = "cross-protocol-" + UUID.randomUUID();
        MetConfiguration incoming = OrderCompositionConfiguration.provider(this.write, this.read, discovered);
        MetConfiguration writing = OrderCompositionConfiguration.consumer(this.write, discovered);
        MetConfiguration reading = OrderCompositionConfiguration.consumer(this.read, discovered);
        incoming.setDefaultGroup(scope).setApplicationName("composition-provider");
        writing.setDefaultGroup(scope).setApplicationName("composition-writer");
        reading.setDefaultGroup(scope).setApplicationName("composition-reader");
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        writing.setDataHome(this.data.newFolder("writer").toPath());
        reading.setDataHome(this.data.newFolder("reader").toPath());

        try {
            // 各应用仅持有自己的注册扩展，注册发现模式不配置提供者的业务地址。
            if (discovered) {
                incoming.addExtension(registries.configuration(this.discovery, scope));
                writing.addExtension(registries.configuration(this.discovery, scope));
                reading.addExtension(registries.configuration(this.discovery, scope));
            }
            try (MetContext provider = incoming.buildContext(); //
                 MetContext writer = writing.buildContext(); //
                 MetContext reader = reading.buildContext()) {
                provider.start();
                writer.start();
                reader.start();

                // 启动后发布唯一业务实例，两个消费者分别订阅当前写协议与读协议。
                OrderDemoServiceImpl orders = new OrderDemoServiceImpl();
                OrderCompositionConfiguration.publish(provider, orders, this.write, this.read);
                MetBindInfo<?> writeBinding = this.write.subscribe(writer);
                MetBindInfo<?> readBinding = this.read.subscribe(reader);
                MetClient writeClient;
                MetClient readClient;
                if (discovered) {
                    RegistryDiscoveryExample.awaitDiscovery(writer, writeBinding);
                    RegistryDiscoveryExample.awaitDiscovery(reader, readBinding);
                    AddressSelectionAwaiter.await(writer, writeBinding, "save");
                    AddressSelectionAwaiter.await(reader, readBinding, "find");
                    writeClient = writer.getRsfClient();
                    readClient = reader.getRsfClient();
                } else {
                    writeClient = writer.getRsfClient(provider.getListenAddress(this.write.protocolName()));
                    readClient = reader.getRsfClient(provider.getListenAddress(this.read.protocolName()));
                }

                // A 保存带二进制附件的订单，B 的网络查询必须读到同一份业务数据。
                Order request = new Order();
                request.setId(4101);
                request.setDescription("saved through " + this.write + ", read through " + this.read);
                request.setAttachment("Meteor 跨协议附件".getBytes(StandardCharsets.UTF_8));
                this.write.save(writeClient, writeBinding, request);
                Order actual = this.read.find(readClient, readBinding, request.getId());
                assertEquals(request.getId(), actual.getId());
                assertEquals(request.getDescription(), actual.getDescription());
                assertArrayEquals(request.getAttachment(), actual.getAttachment());
            }
        } finally {
            // 应用已经退出，再释放当前用例独占的 JDBC 数据库保活连接。
            registries.release(scope);
        }
    }

    private static List<OrderProtocol> selectedProtocols(String property) {
        String filter = System.getProperty(property, "*");

        // 参数拼写错误直接失败，避免空矩阵被误认为已经完成验收。
        List<OrderProtocol> selected = new ArrayList<>();
        boolean all = false;
        for (String token : filter.split(",", -1)) {
            String name = token.trim();
            if (StringUtils.equals(name, "*") || StringUtils.equalsIgnoreCase(name, "all")) {
                all = true;
                continue;
            }
            OrderProtocol match = null;
            for (OrderProtocol protocol : OrderProtocol.values()) {
                if (StringUtils.equalsIgnoreCase(name, protocol.protocolName())) {
                    match = protocol;
                    break;
                }
            }
            if (match == null) {
                throw new IllegalArgumentException("Unknown protocol in " + property + ": " + token);
            }
            if (!selected.contains(match)) {
                selected.add(match);
            }
        }
        if (all) {
            return List.of(OrderProtocol.values());
        }
        return selected;
    }
}
