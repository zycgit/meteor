/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.rpc;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.meteor.*;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Test;
import static org.junit.Assert.*;

/** 通过公开调用入口验证消息服务仍可优先本地直调。 */
public class LocalMessageScenarioTest {
    @Test
    public void localMessageCompletesOnCallingThreadWithoutRemoteAddress() throws Exception {
        // 只配置出站协议，不监听端口，也不为服务提供远程地址。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        AtomicReference<Thread> businessThread = new AtomicReference<>();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<MessageService> service = context.publisher().rsfService(MessageService.class, value -> {
                businessThread.set(Thread.currentThread());
                return "received:" + value;
            }).register();
            context.start();
            assertNull(context.getListenAddress("rsf"));

            // 本地消息在发起调用的线程完成业务，返回的 Future 已经具有业务结果。
            MetFuture future = context.getRsfClient() //
                    .asyncInvoke(service, "accept", new Class<?>[] { String.class }, new Object[] { "local" });
            assertTrue(future.getRequest().isMessage());
            assertNull(future.getRequest().getTargetAddress());
            assertSame(Thread.currentThread(), businessThread.get());
            assertTrue(future.isDone());
            MetResponse response = future.get(1, TimeUnit.SECONDS);
            assertEquals(ProtocolStatus.OK, response.getStatus());
            assertEquals("received:local", response.getData());
        }
    }

    @Test
    public void localMessageBusinessFailureDoesNotContinueToRemoteFilters() throws Exception {
        // 本地提供者抛出业务异常，后续过滤器用于观察是否错误地继续远程调用。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        IllegalStateException businessFailure = new IllegalStateException("message rejected");
        AtomicInteger businessCalls = new AtomicInteger();
        AtomicInteger downstreamCalls = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().bindFilter("ObserveRemoteFallback", (request, response, chain) -> {
                downstreamCalls.incrementAndGet();
                chain.doFilter(request, response);
            });
            MetBindInfo<MessageService> service = context.publisher().rsfService(MessageService.class, value -> {
                businessCalls.incrementAndGet();
                throw businessFailure;
            }).register();
            context.start();
            assertNull(context.getListenAddress("rsf"));

            // 调用方保留原始业务异常，不因缺少地址而改报远程调用失败。
            MetFuture future = context.getRsfClient() //
                    .asyncInvoke(service, "accept", new Class<?>[] { String.class }, new Object[] { "local" });
            assertTrue(future.getRequest().isMessage());
            assertNull(future.getRequest().getTargetAddress());
            assertTrue(future.isDone());
            ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertSame(businessFailure, failure.getCause());
            assertEquals(1, businessCalls.get());
            assertEquals(0, downstreamCalls.get());
        }
    }

    public interface MessageService {
        @MetMessage
        String accept(String value);
    }
}
