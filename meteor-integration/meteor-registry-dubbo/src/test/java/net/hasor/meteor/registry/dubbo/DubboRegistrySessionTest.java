/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.rpc.RpcException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 检查独占 SDK 会话与两种原生目录的合并边界，不经过业务 RPC。 */
public class DubboRegistrySessionTest {
    private static final String SERVICE = "net.hasor.example.registry.dubbo.SessionService";

    @Test
    public void unopenedSessionIsNotAvailable() throws Exception {
        // 尚未打开的会话没有借用其他 SDK 实例的连接状态。
        try (MetContext context = new MetConfiguration().buildContext(); //
             DubboRegistrySession session = new DubboRegistrySession(context, URL.valueOf("zookeeper://host:2181"))) {
            assertFalse(session.isAvailable());
        }
    }

    @Test
    public void providerRegistersInterfaceAndApplicationDirectories() throws Exception {
        // 打开独占会话并一次发布服务地址。
        try (TestingServer server = new TestingServer(); //
             MetContext context = new MetConfiguration().setApplicationName("session-provider").buildContext(); //
             DubboRegistrySession session = new DubboRegistrySession(context, registryUrl(server)); //
             CuratorFramework observer = observer(server)) {
            session.open();
            URL provider = provider(22390, false);
            session.register(provider);
            session.updateInstance();

            // 两种原生目录均对外可见，应用元数据不要求业务端开放额外服务端口。
            assertTrue(session.isAvailable());
            assertEquals(1, observer.getChildren().forPath(interfacePath()).size());
            assertEquals(1, observer.getChildren().forPath("/services/session-provider").size());
        }
    }

    @Test
    public void consumerRegistrationDoesNotCreateProviderInstance() throws Exception {
        // 消费者可以登记原生 consumer URL，但不作为服务提供者发布应用实例。
        try (TestingServer server = new TestingServer(); //
             MetContext context = new MetConfiguration().setApplicationName("session-consumer").buildContext(); //
             DubboRegistrySession session = new DubboRegistrySession(context, registryUrl(server)); //
             CuratorFramework observer = observer(server)) {
            session.open();
            URL consumer = consumer().addParameter("category", "consumers");
            session.register(consumer);
            session.updateInstance();

            // 仅消费者目录存在，未产生 provider 目录和应用实例。
            String consumerPath = "/dubbo/" + SERVICE + "/consumers";
            assertEquals(1, observer.getChildren().forPath(consumerPath).size());
            assertNull(observer.checkExists().forPath("/services/session-consumer"));
        }
    }

    @Test
    public void duplicateDiscoveryAddressesAreMerged() throws Exception {
        // 一个提供者同时登记在接口目录和应用目录。
        try (TestingServer server = new TestingServer(); //
             MetContext context = new MetConfiguration().setApplicationName("session-merged").buildContext(); //
             DubboRegistrySession session = new DubboRegistrySession(context, registryUrl(server))) {
            session.open();
            session.register(provider(22390, true));
            session.updateInstance();

            // 应用 URL 被转换成独立地址，双目录不会让同一提供者重复出现。
            BlockingQueue<List<URL>> notifications = new LinkedBlockingQueue<>();
            NotifyListener listener = notifications::add;
            session.subscribe(consumer(), listener);
            List<URL> providers = lastNotification(notifications);
            assertEquals(1, providers.size());
            assertEquals(22390, providers.get(0).getPort());
            assertEquals("true", providers.get(0).getParameter("ssl-enabled"));
            assertEquals(SERVICE, providers.get(0).getServiceInterface());
            session.unsubscribe(consumer(), listener);
        }
    }

    @Test
    public void interfaceRemovalDoesNotEraseApplicationProviders() throws Exception {
        // 同一地址同时通过两个原生发现模型提供。
        MetConfiguration configuration = new MetConfiguration().setApplicationName("session-source-isolation");
        try (TestingServer server = new TestingServer(); //
             MetContext context = configuration.buildContext(); //
             DubboRegistrySession session = new DubboRegistrySession(context, registryUrl(server)); //
             CuratorFramework observer = observer(server)) {
            session.open();
            session.register(provider(22390, false));
            session.updateInstance();
            BlockingQueue<List<URL>> notifications = new LinkedBlockingQueue<>();
            NotifyListener listener = notifications::add;
            session.subscribe(consumer(), listener);
            assertEquals(1, lastNotification(notifications).size());

            // 仅删除接口节点；随后这一路的空通知仍保留应用目录的地址。
            String child = observer.getChildren().forPath(interfacePath()).get(0);
            notifications.clear();
            observer.delete().forPath(interfacePath() + "/" + child);
            List<URL> remaining = notifications.poll(5, TimeUnit.SECONDS);
            assertNotNull(remaining);
            assertEquals(1, remaining.size());
            assertEquals(22390, remaining.get(0).getPort());
        }
    }

    @Test
    public void sameProviderCanBePublishedAfterLastServiceRemoval() throws Exception {
        // 先创建原生应用实例，再完全撤回最后一个服务。
        try (TestingServer server = new TestingServer(); //
             MetContext context = new MetConfiguration().setApplicationName("session-republish").buildContext(); //
             DubboRegistrySession session = new DubboRegistrySession(context, registryUrl(server)); //
             CuratorFramework observer = observer(server)) {
            session.open();
            URL provider = provider(22390, false);
            session.register(provider);
            session.updateInstance();
            session.unregister(provider);
            session.updateInstance();
            assertTrue(observer.getChildren().forPath("/services/session-republish").isEmpty());

            // 相同地址重新发布后，空版本已经变化，SDK 会重新创建实例而非停留在注销状态。
            session.register(provider);
            session.updateInstance();
            assertEquals(1, observer.getChildren().forPath("/services/session-republish").size());
        }
    }

    @Test
    public void remainingEndpointReplacesRemovedApplicationRepresentative() throws Exception {
        // 应用元数据每个协议服务键选用最早登记的端点，接口目录保留两个端点。
        try (TestingServer server = new TestingServer(); //
             MetContext context = new MetConfiguration().setApplicationName("session-fallback").buildContext(); //
             DubboRegistrySession session = new DubboRegistrySession(context, registryUrl(server)); //
             CuratorFramework observer = observer(server)) {
            session.open();
            URL first = provider(22390, false);
            session.register(first);
            session.register(provider(22391, true));
            session.updateInstance();
            assertEquals(2, observer.getChildren().forPath(interfacePath()).size());

            // 注销首选端点后，剩余 TLS 端点仍能从应用目录发现。
            session.unregister(first);
            session.updateInstance();
            String child = observer.getChildren().forPath(interfacePath()).get(0);
            observer.delete().forPath(interfacePath() + "/" + child);
            BlockingQueue<List<URL>> notifications = new LinkedBlockingQueue<>();
            session.subscribe(consumer(), notifications::add);
            List<URL> remaining = lastNotification(notifications);
            assertEquals(1, remaining.size());
            assertEquals(22391, remaining.get(0).getPort());
            assertEquals("true", remaining.get(0).getParameter("ssl-enabled"));
        }
    }

    @Test
    public void mappingPermissionFailureCanBeRetriedAfterItsRollback() throws Exception {
        // 仅禁止 SDK 读写服务映射，接口提供者目录仍允许登记。
        MetConfiguration configuration = new MetConfiguration().setApplicationName("session-mapping-retry");
        try (TestingServer server = new TestingServer(); //
             MetContext context = configuration.buildContext(); //
             DubboRegistrySession session = new DubboRegistrySession(context, registryUrl(server)); //
             CuratorFramework observer = observer(server)) {
            session.open();
            byte[] credentials = "test-owner:test-password".getBytes(StandardCharsets.UTF_8);
            observer.getZookeeperClient().getZooKeeper().addAuthInfo("digest", credentials);
            String mappingPath = "/dubbo/mapping/" + SERVICE;
            observer.create().creatingParentsIfNeeded() //
                    .withACL(ZooDefs.Ids.CREATOR_ALL_ACL) //
                    .forPath(mappingPath, new byte[0]);
            URL provider = provider(22390, false);

            // 初次映射失败会撤销接口登记，不能留下阻止后续重试的代表端点。
            assertThrows(IllegalStateException.class, () -> session.register(provider));
            assertTrue(observer.getChildren().forPath(interfacePath()).isEmpty());

            // 恢复权限后重试同一个 URL，能够完整创建服务映射与应用实例。
            observer.setACL().withACL(ZooDefs.Ids.OPEN_ACL_UNSAFE).forPath(mappingPath);
            session.register(provider);
            session.updateInstance();
            assertEquals(1, observer.getChildren().forPath("/services/session-mapping-retry").size());
        }
    }

    @Test
    public void emptyConsumerIdentityMatchesAbsentNativeApplicationIdentity() throws Exception {
        // 原生服务没有声明 group/version，而按类引用的消费者使用空字符串身份。
        MetConfiguration configuration = new MetConfiguration().setApplicationName("session-empty-identity");
        try (TestingServer server = new TestingServer(); //
             MetContext context = configuration.buildContext(); //
             DubboRegistrySession session = new DubboRegistrySession(context, registryUrl(server)); //
             CuratorFramework observer = observer(server)) {
            session.open();
            session.register(provider(22390, false));
            session.updateInstance();
            String child = observer.getChildren().forPath(interfacePath()).get(0);
            observer.delete().forPath(interfacePath() + "/" + child);

            // 接口目录已经清空，只能从应用元数据发现未显式声明身份的提供者。
            BlockingQueue<List<URL>> notifications = new LinkedBlockingQueue<>();
            URL consumer = consumer().addParameters(Map.of("group", "", "version", ""));
            session.subscribe(consumer, notifications::add);
            List<URL> remaining = lastNotification(notifications);
            assertEquals(1, remaining.size());
            assertEquals(22390, remaining.get(0).getPort());
        }
    }

    @Test
    public void publishedInstanceUpdateRetriesAfterNodeWritePermissionIsRestored() throws Exception {
        // 先成功发布实例，再仅禁止 SDK 修改实例节点，模拟已经上线后的单次存储写入故障。
        MetConfiguration configuration = new MetConfiguration().setApplicationName("session-update-retry");
        try (TestingServer server = new TestingServer(); //
             MetContext context = configuration.buildContext(); //
             DubboRegistrySession session = new DubboRegistrySession(context, registryUrl(server)); //
             CuratorFramework observer = observer(server)) {
            session.open();
            URL original = provider(22390, false);
            session.register(original);
            session.updateInstance();
            String instances = "/services/session-update-retry";
            String instance = observer.getChildren().forPath(instances).get(0);
            String instancePath = instances + "/" + instance;
            byte[] originalData = observer.getData().forPath(instancePath);
            byte[] credentials = "test-owner:test-password".getBytes(StandardCharsets.UTF_8);
            observer.getZookeeperClient().getZooKeeper().addAuthInfo("digest", credentials);
            List<ACL> permissions = new ArrayList<>(ZooDefs.Ids.CREATOR_ALL_ACL);
            permissions.add(new ACL(ZooDefs.Perms.READ, ZooDefs.Ids.ANYONE_ID_UNSAFE));
            observer.setACL().withACL(permissions).forPath(instancePath);

            // 同地址 TLS 声明变化产生新服务版本；实例节点写入确实因 NoAuth 失败且正文仍旧。
            session.unregister(original);
            session.register(provider(22390, true));
            RpcException failure = assertThrows(RpcException.class, session::updateInstance);
            assertTrue(hasNoAuthCause(failure));
            assertArrayEquals(originalData, observer.getData().forPath(instancePath));

            // 恢复权限后再协调一次，不能被 SDK 已经推进的内存版本误判为无需上报。
            observer.setACL().withACL(ZooDefs.Ids.OPEN_ACL_UNSAFE).forPath(instancePath);
            session.updateInstance();
            byte[] updatedData = observer.getData().forPath(instancePath);
            String originalText = new String(originalData, StandardCharsets.UTF_8);
            String updatedText = new String(updatedData, StandardCharsets.UTF_8);
            assertNotEquals(originalText, updatedText);

            // 删除接口目录以隔离应用发现，实际读取到的新版本必须包含新的 TLS 声明。
            String provider = observer.getChildren().forPath(interfacePath()).get(0);
            observer.delete().forPath(interfacePath() + "/" + provider);
            BlockingQueue<List<URL>> notifications = new LinkedBlockingQueue<>();
            session.subscribe(consumer(), notifications::add);
            List<URL> discovered = lastNotification(notifications);
            assertEquals(1, discovered.size());
            assertTrue(discovered.get(0).getParameter("ssl-enabled", false));
        }
    }

    private static boolean hasNoAuthCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof KeeperException.NoAuthException) {
                return true;
            }
        }
        return false;
    }

    private static URL registryUrl(TestingServer server) {
        return URL.valueOf("zookeeper://" + server.getConnectString()) //
                .addParameter("timeout", 5000) //
                .addParameter("check", true);
    }

    private static URL provider(int port, boolean secure) {
        return new URL("dubbo", "127.0.0.1", port, SERVICE) //
                .addParameter("interface", SERVICE) //
                .addParameter("side", "provider") //
                .addParameter("category", "providers") //
                .addParameter("methods", "echo") //
                .addParameter("ssl-enabled", secure);
    }

    private static URL consumer() {
        return new URL("consumer", "127.0.0.1", 0, SERVICE) //
                .addParameter("interface", SERVICE) //
                .addParameter("protocol", "dubbo") //
                .addParameter("side", "consumer") //
                .addParameter("category", "providers");
    }

    private static String interfacePath() {
        return "/dubbo/" + SERVICE + "/providers";
    }

    private static CuratorFramework observer(TestingServer server) throws InterruptedException {
        ExponentialBackoffRetry retry = new ExponentialBackoffRetry(20, 3);
        CuratorFramework observer = CuratorFrameworkFactory.newClient(server.getConnectString(), retry);
        observer.start();
        assertTrue(observer.blockUntilConnected(5, TimeUnit.SECONDS));
        return observer;
    }

    private static List<URL> lastNotification(BlockingQueue<List<URL>> notifications) throws InterruptedException {
        List<URL> latest = notifications.poll(5, TimeUnit.SECONDS);
        assertNotNull(latest);
        List<URL> next;
        while ((next = notifications.poll()) != null) {
            latest = next;
        }
        return latest;
    }
}
