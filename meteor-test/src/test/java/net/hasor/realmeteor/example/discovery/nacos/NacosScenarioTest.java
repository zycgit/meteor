/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.discovery.nacos;
import java.util.ConcurrentModificationException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.order.Order;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.realmeteor.environment.storage.NacosTestServer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetDirectoryQuery;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntRuleConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.RuleKind;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 独占真实 Nacos 进程；每个场景使用独立分组和缓存，观察自动通知及公开存储契约。 */
public class NacosScenarioTest {
    @ClassRule
    public static final TemporaryFolder       serverData = new TemporaryFolder();
    private static      NacosTestServer nacos;

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    @BeforeClass
    public static void startNacos() throws Exception {
        // 测试持有外部 Nacos 进程的生命周期；应用本身只配置它暴露的服务地址。
        nacos = new NacosTestServer(serverData.newFolder("nacos").toPath());
        nacos.start();
    }

    @AfterClass
    public static void closeNacos() throws Exception {
        if (nacos != null) {
            nacos.close();
        }
    }

    @Test(timeout = 60000)
    public void appliesGovernanceThroughNacosNotification() throws Exception {
        // 核对间隔晚于场景等待上限，元数据只能通过存储变更通知自动生效。
        NacosRegistryConfiguration registry = this.registry();
        MetConfiguration incoming = RegistryDiscoveryExample.provider(registry, "127.0.0.1", 0, 0);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        MetConfiguration outgoing = RegistryDiscoveryExample.consumer(registry, "rsf");
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            // 启动完成后才发布和订阅，同一个订单接口通过 Nacos 发现提供者。
            provider.start();
            consumer.start();
            MetBindInfo<OrderDemoService> subscribed = DirectRpcExample.subscribe(consumer, "rsf");
            MetBindInfo<OrderDemoService> published = DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            RegistryDiscoveryExample.awaitDiscovery(consumer, subscribed);
            Order order = new Order();
            order.setId(1001);
            order.setDescription("nacos-discovered");
            Order saved = consumer.getRsfClient().getRemote(subscribed).save(order);
            assertEquals(order.getDescription(), saved.getDescription());

            // 管理端只写入注册库，提供者通过订阅在原有绑定上应用元数据。
            RegistryAdmin admin = consumer.getExtension(RegistryAdmin.class);
            MetServiceAdmin service = admin.getServiceAdmin(subscribed.getBindID());
            service.putMeta("owner", "orders");
            await(() -> ObjectUtils.equals("orders", published.getMetaData("owner")));
            assertEquals("orders", published.getMetaData("owner"));
        }
    }

    @Test(timeout = 60000)
    public void staleRuleRevisionCannotOverwriteNewBody() throws Exception {
        // 独立存储会话读取两份同版本快照，不启动无关业务应用。
        NacosRegistryConfiguration registry = this.registry();
        try (StorageAccess storage = registry.openStorage()) {
            String serviceId = DirectRpcExample.SERVICE_NAME;
            EntityRecord<EntRuleConfigurationData> initial = new EntityRecord<>();
            EntRuleConfigurationData value = new EntRuleConfigurationData();
            value.setRule("<route><return value='addresses'/></route>");
            value.setEnabled(false);
            initial.setData(value);
            storage.saveRule(null, serviceId, RuleKind.SERVICE_ROUTE, initial);

            // 禁用状态区别于缺失开关的默认启用值，正文与开关均可见后才取得两个快照。
            String initialRule = value.getRule();
            EntityRecord<EntRuleConfigurationData> stale = awaitRule(storage, serviceId, initialRule, false, null);
            EntityRecord<EntRuleConfigurationData> current = awaitRule(storage, serviceId, //
                    initialRule, false, stale.getRevision());
            assertEquals(stale.getRevision(), current.getRevision());

            // 新快照更新规则正文后，旧快照写入必须拒绝，最新正文保持不变。
            String updatedRule = "<route><return value='null'/></route>";
            current.getData().setRule(updatedRule);
            storage.saveRule(null, serviceId, RuleKind.SERVICE_ROUTE, current);
            assertThrows(ConcurrentModificationException.class, //
                    () -> storage.saveRule(null, serviceId, RuleKind.SERVICE_ROUTE, stale));

            // CAS 拒绝旧写入后，等待 Nacos 查询侧可见最新正文；不重试任何写入。
            EntityRecord<EntRuleConfigurationData> saved = awaitRule(storage, serviceId, updatedRule, false, null);
            assertEquals(updatedRule, saved.getData().getRule());
        }
    }

    @Test(timeout = 60000)
    public void providerExitWithdrawsAddressAndMembership() throws Exception {
        // 独立分组内构建提供者和消费者，消费者从订阅事件取得订单地址。
        NacosRegistryConfiguration registry = this.registry();
        MetConfiguration incoming = RegistryDiscoveryExample.provider(registry, "127.0.0.1", 0, 0);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        MetConfiguration outgoing = RegistryDiscoveryExample.consumer(registry, "rsf");
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<OrderDemoService> subscribed = DirectRpcExample.subscribe(consumer, "rsf");
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            RegistryDiscoveryExample.awaitDiscovery(consumer, subscribed);
            String serviceId = subscribed.getBindID();
            RegistryAdmin admin = consumer.getExtension(RegistryAdmin.class);
            MetServiceAdmin service = admin.getServiceAdmin(serviceId);
            MetDirectoryQuery query = new MetDirectoryQuery();
            await(() -> service.providers(null, query).getItems().size() == 1);

            // 关闭提供者后分别等待地址本和管理目录收敛；持久服务身份仍然存在。
            provider.close();
            await(() -> consumer.getUpdater().queryAvailableAddresses(serviceId).isEmpty());
            await(() -> service.providers(null, query).getItems().isEmpty());
            assertEquals(serviceId, service.info().getServiceId());
        }
    }

    private NacosRegistryConfiguration registry() {
        NacosRegistryConfiguration registry = new NacosRegistryConfiguration().setAddress(nacos.address());
        registry.setGroup("order-discovery-" + UUID.randomUUID());
        registry.setManagementToken("order-admin");
        registry.setRefreshMillis(60000);
        return registry;
    }

    private static EntityRecord<EntRuleConfigurationData> awaitRule(StorageAccess storage, String serviceId, //
            String expectedRule, boolean expectedEnabled, String expectedRevision) throws Exception {
        // 真实 Nacos 的查询可晚于发布完成，只轮询读取；存储异常直接向测试传播。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            EntityRecord<EntRuleConfigurationData> record = storage.getRule(null, serviceId, RuleKind.SERVICE_ROUTE);
            if (record != null) {
                boolean bodyMatches = StringUtils.equals(expectedRule, record.getData().getRule());
                boolean enabledMatches = record.getData().isEnabled() == expectedEnabled;
                boolean revisionPresent = record.getRevision() != null;
                boolean revisionMatches = expectedRevision == null //
                        || StringUtils.equals(expectedRevision, record.getRevision());
                if (bodyMatches && enabledMatches && revisionPresent && revisionMatches) {
                    return record;
                }
            }
            if (System.nanoTime() >= deadline) {
                String observed = "missing";
                if (record != null) {
                    EntRuleConfigurationData value = record.getData();
                    observed = "rule=" + value.getRule() + ", enabled=" + value.isEnabled();
                    observed += ", revision=" + record.getRevision();
                }
                throw new AssertionError("Nacos rule snapshot did not become visible: " + serviceId + "; " + observed);
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        // 每次读取最新公开状态，等待上限早于注册中心的周期核对。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue("Nacos notification did not converge", condition.getAsBoolean());
    }
}
