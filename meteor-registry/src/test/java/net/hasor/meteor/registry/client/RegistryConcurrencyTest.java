/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegistryConcurrencyTest {
    @Test
    public void blockedStorageDoesNotBlockAsyncSubmissionOrBusinessRpc() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean();
        MemoryRegistryConfiguration memory = new MemoryRegistryConfiguration();
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(memory, storage -> {
            return (StorageAccess) Proxy.newProxyInstance(StorageAccess.class.getClassLoader(), new Class<?>[] { StorageAccess.class }, (proxy, method, args) -> {
                // 禁用自动通知，仅暂停这次主动拉取；实际数据仍交给正式存储。
                String name = method.getName();
                if (StringUtils.equals(name, "watchConfiguration") || StringUtils.equals(name, "watchProviders")) {
                    return (Cancellable) () -> true;
                }
                if (StringUtils.equals(name, "getConfiguration") && armed.compareAndSet(true, false)) {
                    reading.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                }
                try {
                    return method.invoke(storage, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        });
        registry.setRefreshMillis(600000);
        MetConfiguration serverConfig = new MetConfiguration().addExtension(registry);
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration callerConfig = new MetConfiguration();
        callerConfig.connector("tcp").protocol("rsf");
        ExecutorService submissions = Executors.newFixedThreadPool(2);
        try (MetContext server = serverConfig.buildContext(); //
             MetContext caller = callerConfig.buildContext()) {
            server.publisher().rsfService(Echo.class, value -> value).register();
            caller.publisher().rsfService(Echo.class).register();
            server.start();
            caller.start();
            RegistryClient client = server.getExtension(RegistryClient.class);

            // 拉取停在存储访问阶段，另一次推送仍应立即返回 Future。
            armed.set(true);
            Future<Void> pull = client.pullSync();
            Future<Void> push;
            try {
                assertTrue(reading.await(5, TimeUnit.SECONDS));
                push = submissions.submit(() -> {
                    return client.pushSync();
                }).get(5, TimeUnit.SECONDS);
                assertFalse(push.isDone());

                // 独立上下文通过真实 TCP 调用提供者，不能走本地服务捷径。
                Echo remote = caller.getRsfClient(server.getListenAddress("rsf")).wrapper(Echo.class);
                String response = submissions.submit(() -> remote.echo("available")).get(5, TimeUnit.SECONDS);
                assertEquals("available", response);
            } finally {
                release.countDown();
            }
            pull.get(5, TimeUnit.SECONDS);
            push.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            submissions.shutdownNow();
        }
    }

    public interface Echo {
        String echo(String value);
    }
}
