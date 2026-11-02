/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.admin.MetClusterAdmin;
import net.hasor.meteor.registry.admin.MetEndpointFilter;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.StorageProbe;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RegistryTokenProviderTest {
    @Test
    public void retainedClusterRejectsRevokedCredentialsAndAcceptsRenewal() throws Exception {
        // 使用可撤销凭据创建独立管理会话，保留集群管理对象。
        AtomicReference<String> token = new AtomicReference<>("initial");
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setTokenProvider(token::get);
        try (RegistryAdmin admin = registry.buildAdmin()) {
            MetClusterAdmin cluster = admin.getClusterAdmin();
            cluster.putMeta(Map.of("owner", "before"));

            // 撤销后，已取得对象的查询、状态检查和写入都被拒绝。
            token.set(null);
            assertThrows(SecurityException.class, cluster::fetchMeta);
            assertThrows(SecurityException.class, cluster::status);
            assertThrows(SecurityException.class, () -> cluster.putMeta(Map.of("owner", "denied")));

            // 更新凭据后复用同一对象，失败的写入没有修改存储。
            token.set("renewed");
            assertEquals(Map.of("owner", "before"), cluster.fetchMeta());
            cluster.putMeta(Map.of("owner", "after"));
            assertEquals(Map.of("owner", "after"), cluster.fetchMeta());
        }
    }

    @Test
    public void retainedServiceScopesRejectRevokedCredentials() throws Exception {
        // 服务整体和指定实例上的服务均保留已取得的管理对象。
        AtomicReference<String> token = new AtomicReference<>("initial");
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setTokenProvider(token::get);
        try (RegistryAdmin admin = registry.buildAdmin()) {
            MetServiceAdmin service = admin.getServiceAdmin("echo");
            MetServiceAdmin scoped = admin.getServiceAdmin("echo", "instance-a");
            service.putMeta(Map.of("owner", "service"));
            scoped.putMeta(Map.of("owner", "instance"));

            // 两个范围都在实际调用时读取凭据，不能靠缓存对象继续访问。
            token.set(" ");
            assertThrows(SecurityException.class, service::fetchMeta);
            assertThrows(SecurityException.class, service::disable);
            assertThrows(SecurityException.class, scoped::fetchMeta);
            assertThrows(SecurityException.class, scoped::disable);

            // 恢复后检查未发生越权修改，范围数据仍各自独立。
            token.set("renewed");
            assertTrue(service.isEnable());
            assertTrue(scoped.isEnable());
            assertEquals(Map.of("owner", "service"), service.fetchMeta());
            assertEquals(Map.of("owner", "instance"), scoped.fetchMeta());
        }
    }

    @Test
    public void retainedInstanceRejectsRevokedCredentials() throws Exception {
        // 通过正式扩展启动一个实例，然后取得其管理对象。
        AtomicReference<String> token = new AtomicReference<>("initial");
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setTokenProvider(token::get);
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class).register();
            context.start();
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            MetInstanceAdmin instance = admin.getInstanceAdmin(context.getInstanceID());
            assertFalse(instance.isBlock());

            // 撤销凭据后，缓存对象不能读取实例或修改阻断状态。
            token.set(null);
            assertThrows(SecurityException.class, instance::instance);
            assertThrows(SecurityException.class, instance::block);

            token.set("renewed");
            assertFalse(instance.isBlock());
        }
    }

    @Test
    public void statusPropagatesCredentialSourceFailure() throws Exception {
        // 凭据来源可在会话建立后失败。
        AtomicBoolean unavailable = new AtomicBoolean();
        IllegalStateException expected = new IllegalStateException("Credentials unavailable");
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setTokenProvider(() -> {
            if (unavailable.get()) {
                throw expected;
            }
            return "initial";
        });
        try (RegistryAdmin admin = registry.buildAdmin()) {
            MetClusterAdmin cluster = admin.getClusterAdmin();
            assertTrue(cluster.status().isReachable());

            // 认证故障不能被包装为一次正常的注册中心状态查询。
            unavailable.set(true);
            IllegalStateException failure = assertThrows(IllegalStateException.class, cluster::status);
            assertSame(expected, failure);
        }
    }

    @Test
    public void emptyMetadataRemovalStillChecksCredentials() throws Exception {
        // 空删除不需要读写数据，但仍是管理调用。
        AtomicReference<String> token = new AtomicReference<>("initial");
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setTokenProvider(token::get);
        try (RegistryAdmin admin = registry.buildAdmin()) {
            MetClusterAdmin cluster = admin.getClusterAdmin();
            MetServiceAdmin service = admin.getServiceAdmin("echo");
            token.set(null);

            // 两个范围都必须先校验凭据，再处理空操作。
            assertThrows(SecurityException.class, cluster::removeMeta);
            assertThrows(SecurityException.class, service::removeMeta);
        }
    }

    @Test
    public void filteredOutMembersStillCheckCredentials() throws Exception {
        // 查询范围与成员过滤条件冲突，原查询可以直接返回空页。
        AtomicReference<String> token = new AtomicReference<>("initial");
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setTokenProvider(token::get);
        try (RegistryAdmin admin = registry.buildAdmin()) {
            MetServiceAdmin service = admin.getServiceAdmin("echo", "instance-a");
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setInstanceId("instance-b");
            token.set(null);

            // 提前返回也必须经过认证，提供者和消费者查询使用相同边界。
            assertThrows(SecurityException.class, () -> service.providers(filter, null));
            assertThrows(SecurityException.class, () -> service.consumers(filter, null));
        }
    }

    @Test
    public void invalidCredentialsDoNotOpenIndependentStorage() {
        // 观察独立管理会话是否在认证之前打开存储。
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(new MemoryRegistryConfiguration(), probe::observe);
        registry.setTokenProvider(() -> " ");

        // 来源存在但凭据无效时，仍应在创建存储会话之前拒绝。
        assertThrows(SecurityException.class, registry::buildAdmin);
        assertEquals(0, probe.openedSessions());
    }

    @Test
    public void retainedHandleDoesNotReadCredentialsAfterRegistryCloses() throws Exception {
        // 记录凭据读取次数，取得并实际使用一次管理对象。
        AtomicInteger reads = new AtomicInteger();
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setTokenProvider(() -> {
            reads.incrementAndGet();
            return "initial";
        });
        try (RegistryAdmin admin = registry.buildAdmin()) {
            MetClusterAdmin cluster = admin.getClusterAdmin();
            cluster.fetchMeta();

            // 关闭后优先拒绝访问，不再调用外部凭据来源。
            admin.close();
            int before = reads.get();
            assertThrows(IllegalStateException.class, cluster::fetchMeta);
            assertEquals(before, reads.get());
        }
    }
}
