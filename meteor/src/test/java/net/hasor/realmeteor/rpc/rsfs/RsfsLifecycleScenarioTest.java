/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.rpc.rsfs;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.example.rpc.rsfs.RsfsBlockingProvider;
import net.hasor.example.rpc.rsfs.RsfsBlockingService;
import net.hasor.example.rpc.rsfs.RsfsConfigurations;
import net.hasor.example.rpc.rsfs.RsfsEcho;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import static org.junit.Assert.*;

/** RSF 调用生命周期在 TLS 传输中保持原有协议语义。 */
public class RsfsLifecycleScenarioTest {
    @Test(timeout = 30000)
    public void messageAcknowledgementDoesNotWaitForBusinessCompletion() throws Exception {
        // 使用双向 TLS，业务保持阻塞以区分入队确认和执行完成。
        MetConfiguration incoming = RsfsConfigurations.server("server", "REQUIRE");
        MetConfiguration outgoing = RsfsConfigurations.client("client", "trust");
        RsfsBlockingProvider provider = new RsfsBlockingProvider();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsBlockingService.class, provider).protocol("rsfs").register();
            MetBindInfo<RsfsBlockingService> service = client.publisher() //
                    .rsfService(RsfsBlockingService.class).protocol("rsfs").register();

            // 消息在服务执行结束之前完成回执，并在释放后继续执行。
            MetClient remote = client.getRsfClient(server.bindAddress("rsfs"));
            try {
                MetFuture call = remote.asyncInvoke(service, "signal", new Class<?>[0], new Object[0]);
                assertTrue(provider.entered().await(5, TimeUnit.SECONDS));
                assertNull(call.get(5, TimeUnit.SECONDS).getData());
                assertTrue(call.getRequest().isMessage());
                assertEquals(1L, provider.completed().getCount());
            } finally {
                provider.release();
            }
            assertTrue(provider.completed().await(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 30000)
    public void businessFailureKeepsItsProtocolStatus() throws Exception {
        // 在 JSON 编码和双向 TLS 下发布会抛出业务异常的接口。
        MetConfiguration incoming = RsfsConfigurations.server("server", "REQUIRE");
        MetConfiguration outgoing = RsfsConfigurations.client("client", "trust");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> {
                throw new IllegalArgumentException("business-error:" + value);
            }).protocol("rsfs").serialize("Json").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class) //
                    .protocol("rsfs").serialize("Json").register();

            // 失败必须来自业务响应，不能被误认为 TLS 失败或等待超时。
            MetClient remote = client.getRsfClient(server.bindAddress("rsfs"));
            Class<?>[] parameters = { String.class };
            Object[] arguments = { "invalid" };
            MetFuture call = remote.asyncInvoke(service, "echo", parameters, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof MetException);
            MetException error = (MetException) failure.getCause();
            assertEquals(ProtocolStatus.InvokeError, error.getStatus());
            assertTrue(error.getMessage().contains("business-error:invalid"));
        }
    }

    @Test(timeout = 30000)
    public void concurrentHessianCallsKeepTheirOwnResults() throws Exception {
        // 多个请求共享双向 TLS 连接，并使用 Hessian 编码。
        MetConfiguration incoming = RsfsConfigurations.server("server", "REQUIRE");
        MetConfiguration outgoing = RsfsConfigurations.client("client", "trust");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> "reply:" + value) //
                    .protocol("rsfs").serialize("Hessian").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class) //
                    .protocol("rsfs").serialize("Hessian").register();
            MetClient remote = client.getRsfClient(server.bindAddress("rsfs"));

            // 同时挂起一批请求，再逐一核对关联号对应的业务结果。
            List<MetFuture> calls = new ArrayList<>();
            Class<?>[] parameters = { String.class };
            for (int index = 0; index < 32; index++) {
                Object[] arguments = { "request-" + index };
                calls.add(remote.asyncInvoke(service, "echo", parameters, arguments));
            }
            for (int index = 0; index < calls.size(); index++) {
                assertEquals("reply:request-" + index, calls.get(index).get(5, TimeUnit.SECONDS).getData());
            }
        }
    }

    @Test(timeout = 30000)
    public void cancellingOneCallLeavesTheTlsConnectionUsable() throws Exception {
        // 两个业务线程允许阻塞调用仍在运行时，另一个接口验证取消后的连接可用性。
        MetConfiguration incoming = RsfsConfigurations.server("server", "REQUIRE");
        incoming.setQueueMinPoolSize(2);
        MetConfiguration outgoing = RsfsConfigurations.client("client", "trust");
        RsfsBlockingProvider provider = new RsfsBlockingProvider();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsBlockingService.class, provider).protocol("rsfs").register();
            server.publisher().rsfService(RsfsEcho.class, value -> value).protocol("rsfs").register();
            MetBindInfo<RsfsBlockingService> blocking = client.publisher() //
                    .rsfService(RsfsBlockingService.class).protocol("rsfs").register();
            MetBindInfo<RsfsEcho> echo = client.publisher().rsfService(RsfsEcho.class).protocol("rsfs").register();
            MetClient remote = client.getRsfClient(server.bindAddress("rsfs"));

            // 取消已进入服务的请求，不关闭共享的 TLS 连接。
            try {
                MetFuture call = remote.asyncInvoke(blocking, "execute", new Class<?>[0], new Object[0]);
                assertTrue(provider.entered().await(5, TimeUnit.SECONDS));
                assertTrue(call.cancel());
                assertTrue(call.isDone());
                assertTrue(call.isCancelled());
                assertNull(call.getResult());
                assertEquals("still-open", remote.getRemote(echo).echo("still-open"));
            } finally {
                provider.release();
            }
            assertTrue(provider.completed().await(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 30000)
    public void serverDisconnectFailsThePendingCall() throws Exception {
        // 通过单向 TLS 建立请求，并等待它进入服务方法。
        MetConfiguration incoming = RsfsConfigurations.server("server", "NONE");
        MetConfiguration outgoing = RsfsConfigurations.client(null, "trust");
        RsfsBlockingProvider provider = new RsfsBlockingProvider();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsBlockingService.class, provider).protocol("rsfs").register();
            MetBindInfo<RsfsBlockingService> service = client.publisher() //
                    .rsfService(RsfsBlockingService.class).protocol("rsfs").register();
            MetClient remote = client.getRsfClient(server.bindAddress("rsfs"));
            MetFuture call = remote.asyncInvoke(service, "execute", new Class<?>[0], new Object[0]);

            // 关闭服务端使网络请求失败，不依赖客户端业务超时终结 Future。
            try {
                assertTrue(provider.entered().await(5, TimeUnit.SECONDS));
                server.close();
                ThrowingRunnable pending = () -> call.get(5, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(ExecutionException.class, pending);
                assertNotNull(failure.getCause());
                assertFalse(failure.getCause() instanceof MetTimeoutException);
            } finally {
                provider.release();
            }
        }
    }
}
