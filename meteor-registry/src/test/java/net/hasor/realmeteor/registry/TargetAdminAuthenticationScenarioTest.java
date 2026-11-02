/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.RegistryTokenProvider;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.rpc.ManagementRPC;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 从正式离线扩展通过真实 RSF 验证实例管理认证，不依赖注册中心存储。 */
public class TargetAdminAuthenticationScenarioTest {
    private static MetConfiguration configuration(RegistryTokenProvider tokens, boolean listen) {
        RegistryConfiguration registry = new RegistryConfiguration().setOnline(false);
        registry.setTokenProvider(tokens);
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.setDefaultTimeout(3000);
        configuration.setConnectTimeout(1000);
        var connector = configuration.connector("tcp");
        if (listen) {
            connector.bind("127.0.0.1", 0);
        }
        connector.protocol("rsf");
        return configuration;
    }

    @Test
    public void rawRpcWithoutCredentialCannotReadOrWrite() throws Exception {
        // 原始 RPC 客户端可以绕过 TargetAdmin 封装，但不能绕过服务端过滤器。
        try (MetContext target = configuration(() -> "secret", true).buildContext(); //
             MetContext caller = configuration(null, false).buildContext()) {
            var service = target.publisher().rsfService(Echo.class, value -> value).register();
            target.start();
            caller.start();
            ManagementRPC raw = caller.getRsfClient(target.getListenAddress("rsf")).wrapper(ManagementRPC.class);

            // 不带凭据的查询和写入都返回 401，业务元信息保持原值。
            Exception read = assertThrows(Exception.class, () -> raw.instance(null));
            MetException readError = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(read));
            assertEquals(401, readError.getStatus());
            Exception write = assertThrows(Exception.class, () -> {
                raw.putServiceMeta(null, service.getBindID(), Map.of("bypass", true));
            });
            MetException writeError = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(write));
            assertEquals(401, writeError.getStatus());
            assertNull(service.getMetaData("bypass"));
        }
    }

    @Test
    public void requestOptionsCannotReplaceExplicitCredentialArgument() throws Exception {
        // 旧的凭据请求选项不能代替 RPC 首个参数。
        try (MetContext target = configuration(() -> "secret", true).buildContext(); //
             MetContext caller = configuration(null, false).buildContext()) {
            target.start();
            caller.start();
            ManagementRPC raw = caller.getRsfClient(target.getListenAddress("rsf")) //
                    .withOptions(Map.of("meteor.management.token", "secret")) //
                    .wrapper(ManagementRPC.class);

            Exception failure = assertThrows(Exception.class, () -> raw.setBlock(null, true));
            MetException denied = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(401, denied.getStatus());
            assertFalse(raw.isBlock("secret"));
        }
    }

    @Test
    public void missingServerCredentialDeniesManagementWithoutPreventingStartup() throws Exception {
        // 未配置管理凭据的节点仍可启动，管理 RPC 默认拒绝访问。
        try (MetContext target = configuration(null, true).buildContext(); //
             MetContext caller = configuration(() -> "arbitrary", false).buildContext()) {
            target.start();
            caller.start();
            assertTrue(target.isOnline());
            TargetAdmin admin = caller.getExtension(TargetAdmin.class);
            MetInstanceAdmin instance = admin.getInstanceAdminByAddress(target.getListenAddress("rsf"));

            Exception failure = assertThrows(Exception.class, instance::instance);
            MetException denied = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(401, denied.getStatus());
        }
    }

    @Test
    public void wrongCredentialCannotBlockBusinessCalls() throws Exception {
        // 业务调用和管理调用使用相同监听端口，凭据只附着于管理请求。
        try (MetContext target = configuration(() -> "secret", true).buildContext(); //
             MetContext caller = configuration(() -> "wrong", false).buildContext()) {
            target.publisher().rsfService(Echo.class, value -> value).register();
            caller.publisher().rsfService(Echo.class).register();
            target.start();
            caller.start();
            var address = target.getListenAddress("rsf");
            TargetAdmin admin = caller.getExtension(TargetAdmin.class);
            MetInstanceAdmin instance = admin.getInstanceAdminByAddress(address);

            // 错误凭据不能阻断实例，合法管理和普通业务调用仍可用。
            Exception failure = assertThrows(Exception.class, instance::block);
            MetException denied = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(401, denied.getStatus());
            MetInstanceAdmin valid = admin.withTokenProvider(() -> "secret").getInstanceAdminByAddress(address);
            assertFalse(valid.isBlock());
            assertEquals("business", caller.getRsfClient(address).wrapper(Echo.class).echo("business"));
        }
    }

    @Test
    public void loopbackRpcCannotBypassAuthentication() throws Exception {
        // 同一上下文向自己的监听地址调用管理 RPC，也必须进入服务端认证。
        try (MetContext target = configuration(() -> "secret", true).buildContext()) {
            target.start();
            ManagementRPC raw = target.getRsfClient(target.getListenAddress("rsf")).wrapper(ManagementRPC.class);

            Exception failure = assertThrows(Exception.class, () -> raw.instance(null));
            MetException denied = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(401, denied.getStatus());
        }
    }

    @Test
    public void retainedHandlesReadRotatedCredentialsOnEveryCall() throws Exception {
        // 服务端和调用端各自从动态来源读取凭据，保留已取得的两个管理对象。
        AtomicReference<String> expected = new AtomicReference<>("令牌-initial");
        AtomicReference<String> supplied = new AtomicReference<>(expected.get());
        try (MetContext target = configuration(expected::get, true).buildContext(); //
             MetContext caller = configuration(supplied::get, false).buildContext()) {
            var binding = target.publisher().rsfService(Echo.class, value -> value).register();
            target.start();
            caller.start();
            TargetAdmin admin = caller.getExtension(TargetAdmin.class);
            var address = target.getListenAddress("rsf");
            MetInstanceAdmin instance = admin.getInstanceAdminByAddress(address);
            MetServiceAdmin service = admin.getServiceAdminByAddress(address, binding.getBindID());
            assertEquals(target.getInstanceID(), instance.instance().getInstanceId());
            service.putMeta("owner", "before");

            // 只轮换服务端，缓存对象和已建立的连接都不能继续使用旧凭据。
            expected.set("rotated");
            Exception failure = assertThrows(Exception.class, () -> service.putMeta("owner", "denied"));
            MetException denied = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(401, denied.getStatus());
            assertEquals("before", binding.getMetaData("owner"));

            // 调用端更新来源后直接复用原对象，不必重新建立管理会话。
            supplied.set("rotated");
            assertFalse(instance.isBlock());
            service.putMeta("owner", "after");
            assertEquals(Map.of("owner", "after"), service.fetchMeta());
        }
    }

    @Test
    public void revokedServerCredentialRejectsExistingConnection() throws Exception {
        // 请求凭据仍有效，但服务端撤销了当前管理凭据。
        AtomicReference<String> expected = new AtomicReference<>("secret");
        try (MetContext target = configuration(expected::get, true).buildContext(); //
             MetContext caller = configuration(() -> "secret", false).buildContext()) {
            target.start();
            caller.start();
            TargetAdmin admin = caller.getExtension(TargetAdmin.class);
            MetInstanceAdmin instance = admin.getInstanceAdminByAddress(target.getListenAddress("rsf"));
            assertFalse(instance.isBlock());

            expected.set(null);
            Exception failure = assertThrows(Exception.class, instance::instance);
            MetException denied = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(401, denied.getStatus());
        }
    }

    @Test
    public void serverCredentialSourceFailureDeniesWithoutLeakingDetails() throws Exception {
        // 服务端凭据来源故障时关闭访问，不向客户端返回来源的内部错误。
        AtomicBoolean unavailable = new AtomicBoolean();
        RegistryTokenProvider tokens = () -> {
            if (unavailable.get()) {
                throw new IllegalStateException("private-source-details");
            }
            return "secret";
        };
        try (MetContext target = configuration(tokens, true).buildContext(); //
             MetContext caller = configuration(() -> "secret", false).buildContext()) {
            target.start();
            caller.start();
            TargetAdmin admin = caller.getExtension(TargetAdmin.class);
            MetInstanceAdmin instance = admin.getInstanceAdminByAddress(target.getListenAddress("rsf"));
            assertFalse(instance.isBlock());

            unavailable.set(true);
            Exception failure = assertThrows(Exception.class, instance::instance);
            MetException denied = assertInstanceOf(MetException.class, ExceptionUtils.getRootCause(failure));
            assertEquals(401, denied.getStatus());
            assertFalse(denied.getMessage().contains("private-source-details"));
        }
    }

    @Test
    public void clientCredentialSourceFailureDoesNotSendRequest() throws Exception {
        // 记录服务端认证次数，调用端凭据来源可以在取得对象后失败。
        AtomicInteger serverReads = new AtomicInteger();
        AtomicBoolean unavailable = new AtomicBoolean();
        IllegalStateException expected = new IllegalStateException("Client credentials unavailable");
        RegistryTokenProvider serverTokens = () -> {
            serverReads.incrementAndGet();
            return "secret";
        };
        RegistryTokenProvider clientTokens = () -> {
            if (unavailable.get()) {
                throw expected;
            }
            return "secret";
        };
        try (MetContext target = configuration(serverTokens, true).buildContext(); //
             MetContext caller = configuration(clientTokens, false).buildContext()) {
            target.start();
            caller.start();
            TargetAdmin admin = caller.getExtension(TargetAdmin.class);
            MetInstanceAdmin instance = admin.getInstanceAdminByAddress(target.getListenAddress("rsf"));
            assertFalse(instance.isBlock());

            // 来源故障原样抛出，不能退回缓存 Token 或发出无凭据请求。
            unavailable.set(true);
            int before = serverReads.get();
            IllegalStateException failure = assertThrows(IllegalStateException.class, instance::isBlock);
            assertSame(expected, failure);
            assertEquals(before, serverReads.get());
        }
    }

    @Test
    public void concurrentTargetsKeepCredentialsIndependent() throws Exception {
        // 一个调用上下文同时管理两个使用不同凭据的目标实例。
        try (MetContext first = configuration(() -> "first-secret", true).buildContext(); //
             MetContext second = configuration(() -> "second-secret", true).buildContext(); //
             MetContext caller = configuration(null, false).buildContext()) {
            var firstService = first.publisher().rsfService(Echo.class, value -> value).register();
            var secondService = second.publisher().rsfService(Echo.class, value -> value).register();
            first.start();
            second.start();
            caller.start();
            TargetAdmin admin = caller.getExtension(TargetAdmin.class);
            TargetAdmin firstAdmin = admin.withTokenProvider(() -> "first-secret");
            TargetAdmin secondAdmin = admin.withTokenProvider(() -> "second-secret");
            MetServiceAdmin one = firstAdmin.getServiceAdminByAddress(first.getListenAddress("rsf"), firstService.getBindID());
            MetServiceAdmin two = secondAdmin.getServiceAdminByAddress(second.getListenAddress("rsf"), secondService.getBindID());
            var workers = Executors.newFixedThreadPool(2);
            try {
                var firstWrite = workers.submit(() -> one.putMeta("owner", "first"));
                var secondWrite = workers.submit(() -> two.putMeta("owner", "second"));
                firstWrite.get(10, TimeUnit.SECONDS);
                secondWrite.get(10, TimeUnit.SECONDS);
            } finally {
                workers.shutdownNow();
            }

            // 绑定凭据不会修改共享上下文或另一个管理入口。
            assertEquals("first", firstService.getMetaData("owner"));
            assertEquals("second", secondService.getMetaData("owner"));
            MetInstanceAdmin unbound = admin.getInstanceAdminByAddress(first.getListenAddress("rsf"));
            assertThrows(SecurityException.class, unbound::instance);
        }
    }
}
