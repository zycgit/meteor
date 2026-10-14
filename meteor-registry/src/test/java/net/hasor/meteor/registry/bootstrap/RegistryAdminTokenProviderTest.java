/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.bootstrap;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegistryAdminTokenProviderTest {
    @Test
    public void allAdminEntrypointsReadCurrentTokenAndSupportRotation() throws Exception {
        // 准备可轮换凭据提供者，记录读取次数。
        AtomicReference<String> expected = new AtomicReference<>("Secret-令牌");
        AtomicInteger reads = new AtomicInteger();
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        configuration.setTokenProvider(() -> {
            reads.incrementAndGet();
            return expected.get();
        });
        MetConfiguration application = new MetConfiguration();
        application.connector("tcp").protocol("rsf");
        application.addExtension(configuration);

        try (MetContext context = application.buildContext()) {
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);

            // 注册并启动服务，取得真实的实例和服务标识。
            String serviceId = context.publisher().rsfService(Echo.class).register().getBindID();
            context.start();
            String instanceId = context.getInstanceID();

            // 创建存储和获取扩展不读取凭据；每次获取管理接口才调用配置的提供者。
            assertEquals(0, reads.get());
            List<Runnable> entrypoints = List.of(admin::getClusterAdmin, () -> {
                admin.getInstanceAdmin(instanceId);
            }, () -> admin.getServiceAdmin(serviceId), () -> {
                admin.getServiceAdmin(serviceId, instanceId);
            });
            for (Runnable entrypoint : entrypoints) {
                entrypoint.run();
            }
            assertEquals(4, reads.get());

            // 轮换只更新提供者，调用管理 API 无需重新传入 token。
            expected.set("rotated");
            for (Runnable entrypoint : entrypoints) {
                entrypoint.run();
            }
            assertEquals(8, reads.get());

            // 凭据失效后，所有管理入口都拒绝访问。
            expected.set(null);
            for (Runnable entrypoint : entrypoints) {
                assertThrows(SecurityException.class, entrypoint::run);
            }
            assertEquals(12, reads.get());
        }
    }

    @Test
    public void emptyTokensFromProviderRejectManagementAccess() throws Exception {
        // 准备可返回不同空值的凭据提供者。
        AtomicReference<String> expected = new AtomicReference<>();
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        configuration.setTokenProvider(expected::get);
        MetConfiguration adminConfiguration = new MetConfiguration().addExtension(configuration);

        try (MetContext adminContext = adminConfiguration.buildContext()) {
            RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);

            // 逐个验证 null、空字符串和空白凭据均被拒绝。
            for (String empty : Arrays.asList(null, "", " ", "\t\n")) {
                expected.set(empty);
                assertThrows(SecurityException.class, () -> admin.getClusterAdmin());
            }
        }
    }

    @Test
    public void tokenReadFailuresPropagateAndClosedRegistryDoesNotReadProvider() throws Exception {
        // 准备可切换故障状态的凭据来源。
        IllegalStateException original = new IllegalStateException("Token source unavailable");
        AtomicBoolean unavailable = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        configuration.setTokenProvider(() -> {
            reads.incrementAndGet();
            if (unavailable.get()) {
                throw original;
            }
            return "secret";
        });
        MetConfiguration adminConfiguration = new MetConfiguration().addExtension(configuration);

        try (MetContext adminContext = adminConfiguration.buildContext()) {
            RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);

            // 凭据可用时正常取得管理接口。
            assertNotNull(admin.getClusterAdmin());

            // 来源故障原样上抛，不替换或吞掉异常。
            unavailable.set(true);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> admin.getClusterAdmin());
            assertSame(original, failure);

            // 关闭后先拒绝访问，不再读取凭据来源。
            admin.close();
            int before = reads.get();
            assertThrows(IllegalStateException.class, () -> admin.getClusterAdmin());
            assertEquals(before, reads.get());
        }
    }

    @Test
    public void clientAndStorageDoNotReadManagementTokens() throws Exception {
        // 凭据提供者一旦被调用就失败，用于区分管理与应用接入。
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setTokenProvider(() -> {
            throw new AssertionError("Application and storage entrypoints must not request management tokens");
        });
        MetConfiguration application = new MetConfiguration();
        application.connector("tcp").protocol("rsf");
        application.addExtension(registry);

        try (StorageAccess storage = registry.openStorage(); //
             MetContext context = application.buildContext()) {
            // 直接访问存储和启动应用均不读取管理凭据。
            context.start();
            assertNotNull(storage);
            assertTrue(context.isOnline());
        }
    }
}
