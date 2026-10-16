/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.rpc;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 通过正式发布入口和 TCP 调用验证服务独立线程池。 */
public class ServiceExecutorScenarioTest {
    public interface Work {
        String execute() throws InterruptedException;
    }

    @Test
    public void independentServiceRunsWhileTheSharedWorkerIsOccupied() throws Exception {
        // 默认池和独立池都只允许一个业务线程，阻塞默认服务后检查独立服务。
        MetConfiguration serverConfig = serverConfiguration();
        MetConfiguration clientConfig = clientConfiguration();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Work.class, () -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "shared";
            }).name("shared").register();
            server.publisher().rsfService(Work.class, () -> "independent") //
                    .name("independent").asAloneThreadPool().register();
            MetBindInfo<Work> shared = client.publisher().rsfService(Work.class).name("shared").register();
            MetBindInfo<Work> independent = client.publisher().rsfService(Work.class).name("independent").register();
            server.start();
            client.start();

            // 两个上下文通过网络调用，独立服务不能等待默认池释放。
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            MetFuture waiting = remote.asyncInvoke(shared, "execute", new Class<?>[0], new Object[0]);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                MetFuture response = remote.asyncInvoke(independent, "execute", new Class<?>[0], new Object[0]);
                assertEquals("independent", response.getData(2, TimeUnit.SECONDS));
                assertFalse(waiting.isDone());
            } finally {
                release.countDown();
            }
            assertEquals("shared", waiting.getData(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void sharedServiceRunsWhileAnIndependentWorkerIsOccupied() throws Exception {
        // 独立服务阻塞时，默认业务线程仍应接受普通服务请求。
        MetConfiguration serverConfig = serverConfiguration();
        MetConfiguration clientConfig = clientConfiguration();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Work.class, () -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "independent";
            }).name("independent").asAloneThreadPool().register();
            server.publisher().rsfService(Work.class, () -> "shared").name("shared").register();
            MetBindInfo<Work> independent = client.publisher().rsfService(Work.class).name("independent").register();
            MetBindInfo<Work> shared = client.publisher().rsfService(Work.class).name("shared").register();
            server.start();
            client.start();

            // 不依赖线程名判断隔离，直接观察另一服务是否及时返回。
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            MetFuture waiting = remote.asyncInvoke(independent, "execute", new Class<?>[0], new Object[0]);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                MetFuture response = remote.asyncInvoke(shared, "execute", new Class<?>[0], new Object[0]);
                assertEquals("shared", response.getData(2, TimeUnit.SECONDS));
                assertFalse(waiting.isDone());
            } finally {
                release.countDown();
            }
            assertEquals("independent", waiting.getData(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void independentServicesUseDifferentWorkers() throws Exception {
        // 两个服务都选择独立池，它们之间也不能共用业务队列。
        MetConfiguration serverConfig = serverConfiguration();
        MetConfiguration clientConfig = clientConfiguration();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Work.class, () -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "first";
            }).name("first").asAloneThreadPool().register();
            server.publisher().rsfService(Work.class, () -> "second") //
                    .name("second").asAloneThreadPool().register();
            MetBindInfo<Work> first = client.publisher().rsfService(Work.class).name("first").register();
            MetBindInfo<Work> second = client.publisher().rsfService(Work.class).name("second").register();
            server.start();
            client.start();

            // 第一个服务保持执行中时，第二个服务仍完成自己的调用。
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            MetFuture waiting = remote.asyncInvoke(first, "execute", new Class<?>[0], new Object[0]);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                MetFuture response = remote.asyncInvoke(second, "execute", new Class<?>[0], new Object[0]);
                assertEquals("second", response.getData(2, TimeUnit.SECONDS));
                assertFalse(waiting.isDone());
            } finally {
                release.countDown();
            }
            assertEquals("first", waiting.getData(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void closingContextInterruptsAndReleasesAnIndependentWorker() throws Exception {
        // 在真实业务方法中记录执行线程，关闭后观察中断和线程退出。
        MetConfiguration serverConfig = serverConfiguration();
        MetConfiguration clientConfig = clientConfiguration();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Work.class, () -> {
                worker.set(Thread.currentThread());
                entered.countDown();
                try {
                    new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                    return "unexpected";
                } catch (InterruptedException failure) {
                    interrupted.countDown();
                    throw failure;
                }
            }).asAloneThreadPool().register();
            MetBindInfo<Work> service = client.publisher().rsfService(Work.class).register();
            server.start();
            client.start();

            // 关闭应释放实际创建的独立池，不留下非守护业务线程。
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            remote.asyncInvoke(service, "execute", new Class<?>[0], new Object[0]);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            server.close();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            worker.get().join(2000);
            assertFalse(worker.get().isAlive());
        }
    }

    private static MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setQueueMinPoolSize(1).setQueueMaxPoolSize(1).setQueueMaxSize(1);
        configuration.setDefaultTimeout(10000);
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        return configuration;
    }

    private static MetConfiguration clientConfiguration() {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultTimeout(10000);
        configuration.connector("tcp").protocol("rsf");
        return configuration;
    }
}
