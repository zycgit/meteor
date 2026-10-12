/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.SendLimitPolicy;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcInteropTest.Message;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcFlowTest {
    public interface Messages {
        Flow.Publisher<Message> list(Message query);

        Message collect(Flow.Publisher<Message> messages);

        Flow.Publisher<Message> exchange(Flow.Publisher<Message> messages);

        Flow.Publisher<Message> never(Message query);

        Flow.Publisher<Message> broken(Message query);
    }

    public static final class Service implements Messages {
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final CountDownLatch producing = new CountDownLatch(1);
        private final AtomicInteger  produced  = new AtomicInteger();

        public Flow.Publisher<Message> list(Message query) {
            return MetCaller.fromIterator(() -> IntStream.range(0, query.getCount()).mapToObj(index -> {
                this.produced.incrementAndGet();
                return new Message(query.getValue(), index);
            }).iterator());
        }

        public Message collect(Flow.Publisher<Message> messages) {
            AtomicInteger sum = new AtomicInteger();
            try {
                MetCaller.consume(messages, value -> sum.addAndGet(value.getCount())).get(5, TimeUnit.SECONDS);
                return new Message("sum", sum.get());
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        public Flow.Publisher<Message> exchange(Flow.Publisher<Message> messages) {
            return messages;
        }

        public Flow.Publisher<Message> never(Message query) {
            return subscriber -> {
                this.producing.countDown();
                subscriber.onSubscribe(new Flow.Subscription() {
                    public void request(long n) {
                    }

                    public void cancel() {
                        Service.this.cancelled.countDown();
                    }
                });
            };
        }

        public Flow.Publisher<Message> broken(Message query) {
            return MetCaller.fromIterator(() -> {
                throw new IllegalStateException("source failure 中文");
            });
        }
    }

    @Test(timeout = 20000)
    public void serverClientAndBidirectionalStreamsUseTheExistingServiceProxy() throws Exception {
        MetConfiguration incoming = this.configuration(true, 5000);
        MetConfiguration outgoing = this.configuration(false, 5000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            Messages remote = this.publish(server, client, new Service());
            List<Message> values = new ArrayList<>();
            MetCaller.consume(remote.list(new Message("中文", 4)), values::add).get(5, TimeUnit.SECONDS);
            assertEquals(4, values.size());
            assertEquals(3, values.get(3).getCount());
            assertEquals("中文", values.get(3).getValue());

            // 请求方向结束后才得到普通结果，空请求流也是有效输入。
            Message sum = remote.collect(MetCaller.fromIterable(values));
            assertEquals(6, sum.getCount());
            assertEquals(0, remote.collect(MetCaller.fromIterable(List.of())).getCount());

            // 双向流复用相同 Java 接口和 Caller/Dispatcher。
            List<Message> replies = new ArrayList<>();
            MetCaller.consume(remote.exchange(MetCaller.fromIterable(values)), replies::add).get(5, TimeUnit.SECONDS);
            assertEquals(4, replies.size());
            assertEquals(3, replies.get(3).getCount());
            MetCaller.consume(remote.list(new Message("empty", 0)), value -> fail()).get(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void demandControlsDeliveryAcrossHttp2WindowsAndRejectsSecondSubscriber() throws Exception {
        try (MetContext server = this.configuration(true, 10000).buildContext(); //
             MetContext client = this.configuration(false, 10000).buildContext()) {
            Service implementation = new Service();
            Messages remote = this.publish(server, client, implementation);
            Flow.Publisher<Message> result = remote.list(new Message("x".repeat(200000), 20));
            Probe probe = new Probe();
            result.subscribe(probe);
            assertTrue(probe.subscribed.await(2, TimeUnit.SECONDS));
            assertNull(probe.values.poll(100, TimeUnit.MILLISECONDS));
            assertTrue(implementation.produced.get() < 20);
            probe.subscription.request(1);
            Message first = probe.values.poll(3, TimeUnit.SECONDS);
            assertNotNull(first);
            assertEquals(200000, first.getValue().length());
            assertNull(probe.values.poll(100, TimeUnit.MILLISECONDS));

            Probe duplicate = new Probe();
            result.subscribe(duplicate);
            assertThrows(Exception.class, () -> duplicate.finished.get(2, TimeUnit.SECONDS));
            assertTrue(duplicate.finished.getCause() instanceof IllegalStateException);
            probe.subscription.request(19);
            for (int index = 1; index < 20; index++) {
                Message next = probe.values.poll(3, TimeUnit.SECONDS);
                assertNotNull(next);
                assertEquals(index, next.getCount());
            }
            probe.finished.get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void cancellationFailureAndInvalidDemandReleaseTheRpc() throws Exception {
        Service implementation = new Service();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            Messages remote = this.publish(server, client, implementation);
            Probe cancelled = new Probe();
            remote.never(new Message()).subscribe(cancelled);
            assertTrue(cancelled.subscribed.await(2, TimeUnit.SECONDS));
            assertTrue(implementation.producing.await(2, TimeUnit.SECONDS));
            cancelled.subscription.cancel();
            assertTrue(implementation.cancelled.await(3, TimeUnit.SECONDS));

            Probe failure = new Probe();
            remote.broken(new Message()).subscribe(failure);
            failure.subscription.request(1);
            assertThrows(Exception.class, () -> failure.finished.get(3, TimeUnit.SECONDS));
            assertTrue(failure.finished.getCause().getMessage().contains("source failure"));

            Probe invalid = new Probe();
            remote.list(new Message("bad demand", 2)).subscribe(invalid);
            invalid.subscription.request(0);
            assertThrows(Exception.class, () -> invalid.finished.get(3, TimeUnit.SECONDS));
            assertTrue(invalid.finished.getCause() instanceof IllegalArgumentException);
            MetCaller.consume(remote.list(new Message("after failure", 1)), value -> {
                assertEquals("after failure", value.getValue());
            }).get(3, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 15000)
    public void deadlineAndContextCloseFailStreamsEvenWithoutDemand() throws Exception {
        Service implementation = new Service();
        try (MetContext server = this.configuration(true, 600).buildContext(); //
             MetContext client = this.configuration(false, 600).buildContext()) {
            Messages remote = this.publish(server, client, implementation);
            Probe timeout = new Probe();
            remote.never(new Message()).subscribe(timeout);
            assertThrows(Exception.class, () -> timeout.finished.get(3, TimeUnit.SECONDS));
            assertTrue(implementation.cancelled.await(3, TimeUnit.SECONDS));
        }

        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            Messages remote = this.publish(server, client, new Service());
            Probe closing = new Probe();
            remote.never(new Message()).subscribe(closing);
            client.close();
            assertThrows(Exception.class, () -> closing.finished.get(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void blockedConsumerDoesNotPreventDeadlineFromReleasingTheRequestPermit() throws Exception {
        MetConfiguration configuration = this.configuration(false, 700);
        configuration.setMaximumRequest(1).setSendLimitPolicy(SendLimitPolicy.WaitSecond);
        CountDownLatch processing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (MetContext server = this.configuration(true, 3000).buildContext(); //
             MetContext client = configuration.buildContext()) {
            Messages remote = this.publish(server, client, new Service());
            Future<Void> blocked = MetCaller.consume(remote.list(new Message("x".repeat(200000), 20)), value -> {
                processing.countDown();
                try {
                    release.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(processing.await(3, TimeUnit.SECONDS));

            // 唯一配额被首个流持有，截止时间必须先释放它，后续 RPC 才能执行。
            MetCaller.consume(remote.list(new Message("after deadline", 1)), value -> {
                assertEquals("after deadline", value.getValue());
            }).get(3, TimeUnit.SECONDS);
            assertEquals(1, release.getCount());
            release.countDown();
            assertThrows(Exception.class, () -> blocked.get(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    private MetConfiguration configuration(boolean server, int timeout) {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultProtocol("grpc");
        configuration.setDefaultTimeout(timeout);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (server) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("grpc").option("streamBody", true);
        return configuration;
    }

    private Messages publish(MetContext server, MetContext client, Messages implementation) throws Exception {
        server.publisher().rsfService(Messages.class, implementation).aliasName("grpc", "example.Messages").serialize("Json").register();
        MetBindInfo<Messages> info = client.publisher().rsfService(Messages.class).aliasName("grpc", "example.Messages").serialize("Json").register();
        server.start();
        client.start();
        return client.getRsfClient(server.bindAddress("grpc")).getRemote(info);
    }

    private static final class Probe implements Flow.Subscriber<Message> {
        private final LinkedBlockingQueue<Message> values     = new LinkedBlockingQueue<>();
        private final CountDownLatch               subscribed = new CountDownLatch(1);
        private final BasicFuture<Void>            finished   = new BasicFuture<>();
        private       Flow.Subscription            subscription;

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            this.subscribed.countDown();
        }

        public void onNext(Message value) {
            this.values.add(value);
        }

        public void onError(Throwable failure) {
            this.finished.failed(failure);
        }

        public void onComplete() {
            this.finished.completed(null);
        }
    }
}
