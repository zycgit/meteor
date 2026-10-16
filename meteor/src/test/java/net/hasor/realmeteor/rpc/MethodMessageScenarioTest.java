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
import java.util.concurrent.TimeoutException;
import net.hasor.meteor.*;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Test;
import static org.junit.Assert.*;

/** 同一接口的方法通过 TCP RSF 分别使用消息调用和普通调用。 */
public class MethodMessageScenarioTest {
    @Test
    public void annotatedMethodReturnsBeforeBusinessCompletion() throws Exception {
        // 两个上下文发布和订阅同一混合接口，整个服务不配置消息模式。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.setDefaultTimeout(10000);
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.setDefaultTimeout(10000);
        clientConfig.connector("tcp").protocol("rsf");
        BlockingService provider = new BlockingService();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(MixedService.class, provider).register();
            MetBindInfo<MixedService> service = client.publisher().rsfService(MixedService.class).register();
            assertFalse(service.isMessage());

            // 消息方法已经开始且保持阻塞时，入队确认仍应让调用方完成等待。
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            try {
                MetFuture invocation = remote.asyncInvoke(service, "signal", new Class<?>[0], new Object[0]);
                assertTrue(provider.entered.await(3, TimeUnit.SECONDS));
                MetResponse response = invocation.get(3, TimeUnit.SECONDS);
                assertTrue(invocation.isDone());
                assertTrue(invocation.getRequest().isMessage());
                assertNull(response.getData());
                assertEquals(1L, provider.completed.getCount());
            } finally {
                provider.release.countDown();
            }

            // 入队回执不会取消服务端业务，释放后服务继续完成执行。
            assertTrue(provider.completed.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    public void unannotatedMethodWaitsForItsBusinessResult() throws Exception {
        // 同一接口保留消息方法，但普通方法没有注解且服务未配置消息模式。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.setDefaultTimeout(10000);
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.setDefaultTimeout(10000);
        clientConfig.connector("tcp").protocol("rsf");
        BlockingService provider = new BlockingService();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(MixedService.class, provider).register();
            MetBindInfo<MixedService> service = client.publisher().rsfService(MixedService.class).register();

            // 普通方法收到入队回执后仍等待业务结果，不能因同接口的消息注解提前结束。
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            MetFuture invocation = remote.asyncInvoke(service, "execute", new Class<?>[0], new Object[0]);
            try {
                assertTrue(provider.entered.await(3, TimeUnit.SECONDS));
                assertFalse(invocation.getRequest().isMessage());
                assertThrows(TimeoutException.class, () -> invocation.get(200, TimeUnit.MILLISECONDS));
                assertEquals(1L, provider.completed.getCount());
            } finally {
                provider.release.countDown();
            }

            // 业务完成才返回 OK 及真实返回值。
            MetResponse response = invocation.get(3, TimeUnit.SECONDS);
            assertEquals(ProtocolStatus.OK, response.getStatus());
            assertEquals("completed", response.getData());
        }
    }

    public interface MixedService {
        @MetMessage
        void signal() throws InterruptedException;

        String execute() throws InterruptedException;
    }

    public static class BlockingService implements MixedService {
        private final CountDownLatch entered   = new CountDownLatch(1);
        private final CountDownLatch release   = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void signal() throws InterruptedException {
            this.awaitRelease();
        }

        @Override
        public String execute() throws InterruptedException {
            this.awaitRelease();
            return "completed";
        }

        private void awaitRelease() throws InterruptedException {
            // 用业务入口信号固定调用时序，并限制等待时间以便失败时释放资源。
            this.entered.countDown();
            try {
                if (!this.release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Business method was not released");
                }
            } finally {
                this.completed.countDown();
            }
        }
    }
}
