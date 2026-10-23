/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.hasor.example.contract.complex.ComplexCustomer;
import net.hasor.example.contract.complex.ComplexDocument;
import net.hasor.example.contract.complex.ComplexIndex;
import net.hasor.example.contract.complex.ComplexLine;
import net.hasor.example.contract.complex.ComplexValueProfile;
import net.hasor.example.contract.complex.ComplexValueService;
import net.hasor.example.contract.complex.ComplexValueServiceImpl;
import net.hasor.example.contract.complex.ComplexValueVectors;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.realmeteor.environment.discovery.AddressSelectionAwaiter;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 第二层公共向量：嵌套对象与集合、声明泛型的 Map 各自独立报告。 */
@RunWith(Parameterized.class)
public class ComplexValueMatrixTest {
    @ClassRule
    public static final TemporaryFolder environmentData = new TemporaryFolder();
    private static OrderRegistryEnvironment environment;

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();
    private final ComplexValueProfile profile;
    private final OrderDiscovery discovery;
    private String scope;
    private MetConfiguration incoming;
    private MetConfiguration outgoing;

    public ComplexValueMatrixTest(ComplexValueProfile profile, OrderDiscovery discovery) {
        this.profile = profile;
        this.discovery = discovery;
    }

    @Parameterized.Parameters(name = "complex/self/{0}/{1}")
    public static Collection<Object[]> configurations() {
        // 这里只枚举 profile 和发现方式；两个业务目标仍然是独立的测试方法。
        List<String> protocols = selection("meteor.matrix.protocols");
        List<String> profiles = selection("meteor.matrix.complexProfiles");
        List<String> discoveries = selection("meteor.matrix.discoveries");
        List<Object[]> result = new ArrayList<>();
        for (ComplexValueProfile profile : ComplexValueProfile.values()) {
            if (!selected(profile.protocolName(), protocols) && !selected(profile.toString(), protocols)) {
                continue;
            }
            if (!selected(profile.toString(), profiles)) {
                continue;
            }
            for (OrderDiscovery discovery : OrderDiscovery.values()) {
                if (selected(discovery.toString(), discoveries)) {
                    result.add(new Object[] { profile, discovery });
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("The requested complex profile/discovery matrix has no matching cases");
        }
        return result;
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

    @Before
    public void configureApplications() throws Exception {
        // 每个方法独占缓存、服务分组和注册空间，不继承上一用例发现的业务地址。
        this.scope = "complex-" + UUID.randomUUID();
        this.incoming = this.profile.providerConfiguration();
        this.incoming.setDefaultGroup(this.scope).setApplicationName("complex-provider");
        this.incoming.setDataHome(this.data.newFolder("provider").toPath());
        this.outgoing = this.profile.consumerConfiguration();
        this.outgoing.setDefaultGroup(this.scope).setApplicationName("complex-consumer");
        this.outgoing.setDataHome(this.data.newFolder("consumer").toPath());

        // 注册模式只有存储配置；独立 RSF 挂载只承载内部管理服务。
        if (this.discovery != OrderDiscovery.DIRECT) {
            RegistryConfiguration registry = environment.configuration(this.discovery, this.scope);
            this.incoming.addExtension(registry);
            this.outgoing.addExtension(registry);
            if (this.profile != ComplexValueProfile.RSF_JSON) {
                this.incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
                this.outgoing.connector("tcp").protocol("rsf");
            }
        }
    }

    @After
    public void releaseDatabase() throws Exception {
        // 业务上下文已退出，再释放本方法持有的独立内存数据库。
        environment.release(this.scope);
    }

    @Test(timeout = 120000)
    public void nestedObjectsAndOrderedListsPreserveTheirDeclaredTypes() throws Exception {
        // 两个应用通过正式 bootstrap 启动；订阅时尚未发布本次业务服务。
        try (MetContext provider = this.incoming.buildContext(); //
             MetContext consumer = this.outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<?> subscription = this.profile.subscribe(consumer);
            assertTrue(consumer.getUpdater().queryAvailableAddresses(subscription.getBindID()).isEmpty());
            assertNull(consumer.getListenAddress(this.profile.protocolName()));
            this.profile.publish(provider, new ComplexValueServiceImpl());

            // 只有直连可以取得监听地址；注册模式通过真实自动订阅获得业务地址。
            MetClient caller = this.remote(provider, consumer, subscription, "acceptDocument");
            ComplexValueService remote = this.profile.remote(caller, subscription);
            ComplexDocument request = ComplexValueVectors.document();
            ComplexDocument actual = remote.acceptDocument(request);

            // 服务端生成收据，并重新建立嵌套响应，字段和两个集合的位置都保持原值。
            assertEquals("document:42:7", actual.getReceipt());
            assertEquals(request.getDocumentId(), actual.getDocumentId());
            ComplexCustomer expectedCustomer = request.getCustomer();
            ComplexCustomer actualCustomer = actual.getCustomer();
            assertEquals(expectedCustomer.getCode(), actualCustomer.getCode());
            assertEquals(expectedCustomer.getName(), actualCustomer.getName());
            assertEquals(expectedCustomer.isVerified(), actualCustomer.isVerified());
            assertEquals(expectedCustomer.getRating(), actualCustomer.getRating(), 0.0);
            assertEquals(request.getRoute(), actual.getRoute());
            assertEquals(request.getLines().size(), actual.getLines().size());
            for (int index = 0; index < request.getLines().size(); index++) {
                ComplexLine expected = request.getLines().get(index);
                ComplexLine line = actual.getLines().get(index);
                assertEquals(expected.getSku(), line.getSku());
                assertEquals(expected.getQuantity(), line.getQuantity());
                assertEquals(expected.getUnitPrice(), line.getUnitPrice(), 0.0);
            }
        }
    }

    @Test(timeout = 120000)
    public void typedMapsPreserveDtoAndInt32Values() throws Exception {
        // 每次使用独立应用和业务实例，Map 场景不复用嵌套集合场景的调用结果。
        try (MetContext provider = this.incoming.buildContext(); //
             MetContext consumer = this.outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<?> subscription = this.profile.subscribe(consumer);
            assertTrue(consumer.getUpdater().queryAvailableAddresses(subscription.getBindID()).isEmpty());
            assertNull(consumer.getListenAddress(this.profile.protocolName()));
            this.profile.publish(provider, new ComplexValueServiceImpl());

            // 共享向量的 key 满足 XML localName，SOAP literal 不依赖非法 QName 容错。
            MetClient caller = this.remote(provider, consumer, subscription, "acceptIndex");
            ComplexValueService remote = this.profile.remote(caller, subscription);
            ComplexIndex request = ComplexValueVectors.index();
            ComplexIndex actual = remote.acceptIndex(request);

            // 收据证明服务端读取了 Integer 值，返回 Map 的 DTO 值保持具体声明类型。
            assertEquals("index:9:12", actual.getReceipt());
            assertEquals(request.getRevision(), actual.getRevision());
            assertEquals(request.getStockByCode(), actual.getStockByCode());
            assertEquals(request.getLinesByCode().keySet(), actual.getLinesByCode().keySet());
            for (Map.Entry<String, ComplexLine> entry : request.getLinesByCode().entrySet()) {
                ComplexLine expected = entry.getValue();
                ComplexLine line = actual.getLinesByCode().get(entry.getKey());
                assertEquals(expected.getSku(), line.getSku());
                assertEquals(expected.getQuantity(), line.getQuantity());
                assertEquals(expected.getUnitPrice(), line.getUnitPrice(), 0.0);
            }
        }
    }

    private MetClient remote(MetContext provider, MetContext consumer, MetBindInfo<?> subscription, //
            String methodName) throws Exception {
        if (this.discovery == OrderDiscovery.DIRECT) {
            return consumer.getRsfClient(provider.getListenAddress(this.profile.protocolName()));
        }

        // 只等待自动发现，不主动拉取或向消费者注入提供者地址。
        RegistryDiscoveryExample.awaitDiscovery(consumer, subscription);
        var addresses = consumer.getUpdater().queryAvailableAddresses(subscription.getBindID());
        assertEquals(1, addresses.size());
        assertEquals(this.profile.protocolName(), addresses.get(0).getSchema());
        AddressSelectionAwaiter.await(consumer, subscription, methodName);
        return consumer.getRsfClient();
    }

    private static List<String> selection(String property) {
        String value = System.getProperty(property, "*").toLowerCase(Locale.ROOT);
        return Arrays.stream(value.split(",")).map(String::trim).toList();
    }

    private static boolean selected(String value, List<String> selection) {
        return selection.contains("*") || selection.contains(value);
    }
}
