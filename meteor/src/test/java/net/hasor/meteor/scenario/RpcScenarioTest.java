/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.FutureCallback;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 接口代理、泛化调用、并发 Future、回调和消息调用。 */
public class RpcScenarioTest {
    @Test
    public void callByProxyByIdentityAndByMethodAgainstAnAddressBook() throws Exception {
        // 服务端开启监听，客户端只配置主动连接能力。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.publisher().rsfService(Echo.class, value -> "server:" + value).register();
            server.start();
            var subscription = client.publisher().rsfService(Echo.class);
            MetBindInfo<Echo> service = subscription.bindAddress(server.bindAddress("rsf")).register();
            client.start();

            // 不指定远端地址时，从该服务的地址本选择服务器。
            MetClient caller = client.getRsfClient();
            Echo proxy = caller.getRemote(service);
            assertEquals("server:proxy", proxy.echo("proxy"));
            Echo byId = caller.getRemoteByID(service.getBindID());
            assertEquals("server:id", byId.echo("id"));
            EchoView view = caller.wrapper(service, EchoView.class);
            assertEquals("server:view", view.echo("view"));
            Class<?>[] types = { String.class };
            Object[] arguments = { "dynamic" };
            assertEquals("server:dynamic", caller.syncInvoke(service, "echo", types, arguments));
            assertNull(client.bindAddress("rsf"));
        }
    }

    @Test
    public void multiplexCallsAndObserveTheirIndividualCompletions() throws Exception {
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.publisher().rsfService(Echo.class, value -> "reply:" + value).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();

            // 同一客户端并发提交多个请求，各 Future 对应自己的结果。
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            Class<?>[] types = { String.class };
            List<MetFuture> requests = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                Object[] arguments = { "order-" + index };
                requests.add(remote.asyncInvoke(service, "echo", types, arguments));
            }
            for (int index = 0; index < requests.size(); index++) {
                assertEquals("reply:order-" + index, requests.get(index).getData(5, TimeUnit.SECONDS));
            }

            // 不阻塞调用线程时，通过回调处理结果或失败。
            BasicFuture<Object> result = new BasicFuture<>();
            FutureCallback<Object> callback = new FutureCallback<>() {
                public void completed(Object value) {
                    result.completed(value);
                }

                public void failed(Throwable failure) {
                    result.failed(failure);
                }
            };
            remote.callbackInvoke(service, "echo", types, new Object[] { "callback" }, callback);
            assertEquals("reply:callback", result.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void messageAcknowledgementDoesNotWaitForBusinessCompletion() throws Exception {
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 消息服务收到请求后执行后台业务；单独线程池隔离该服务。
            Signal target = value -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } finally {
                    finished.countDown();
                }
            };
            var publication = server.publisher().rsfService(Signal.class, target);
            publication.asMessage().asAloneThreadPool().register();
            MetBindInfo<Signal> service = client.publisher().rsfService(Signal.class).asMessage().register();
            server.start();
            client.start();

            // 收到确认时业务尚未结束，之后由业务自己的完成信号确认执行。
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            Class<?>[] types = { String.class };
            MetFuture accepted = remote.asyncInvoke(service, "accept", types, new Object[] { "event" });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertNull(accepted.getData(3, TimeUnit.SECONDS));
            assertEquals(1, finished.getCount());
            release.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    public interface Echo {
        String echo(String value);
    }

    public interface EchoView {
        String echo(String value);
    }

    public interface Signal {
        void accept(String value) throws InterruptedException;
    }
}
