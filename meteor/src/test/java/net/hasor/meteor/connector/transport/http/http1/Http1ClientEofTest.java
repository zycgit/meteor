/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http1;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.SocketRuntime;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.TransportPipeline;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 以明确的任务顺序验证完整响应、发送结果与连接 EOF 的交错。 */
public class Http1ClientEofTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void completeBufferedResponseWaitsForPendingSendAfterEof() throws Exception {
        try (Fixture test = new Fixture()) {
            // 正常入队请求，暂不交付底层发送完成通知。
            Future<Void> sent = test.send();
            HttpResponse response = buffered();
            test.channel.received(test.socket, response, false);
            test.eof(test.socket);
            test.executor.drain();

            // EOF 不能把完整响应改成失败；发送尚未结束时也不提前完成交换。
            assertSame(response, test.exchange.response().getResult());
            assertFalse(sent.isDone());
            assertFalse(test.exchange.completion().isDone());

            // 原发送正常结束后，由原有 written 回调完成同一交换。
            test.socket.written.completed(test.socket);
            test.executor.drain();
            assertSuccessful(sent);
            assertSuccessful(test.exchange.completion());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void completeBufferedResponseSurvivesEofAfterSend() throws Exception {
        try (Fixture test = new Fixture()) {
            // 发送结果先到，随后完整响应和 EOF 连续进入同一串行执行器。
            Future<Void> sent = test.send();
            test.socket.written.completed(test.socket);
            test.executor.drain();
            HttpResponse response = buffered();
            test.channel.received(test.socket, response, false);
            test.eof(test.socket);
            test.executor.drain();

            // 已满足声明长度的响应保持成功，不受连接随后关闭影响。
            assertSame(response, test.exchange.response().getResult());
            assertSuccessful(sent);
            assertSuccessful(test.exchange.completion());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sendFailureWithoutResponseKeepsOriginalCause() throws Exception {
        try (Fixture test = new Fixture()) {
            // 还没有响应时，真实发送失败先于连接 EOF 到达。
            Future<Void> sent = test.send();
            IOException failure = new IOException("Request write failed");
            test.socket.written.failed(failure);
            test.executor.drain();
            test.eof(test.socket);
            test.executor.drain();

            // 连接关闭不覆盖原始发送错误。
            assertSame(failure, sent.getCause());
            assertSame(failure, test.exchange.response().getCause());
            assertSame(failure, test.exchange.completion().getCause());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sendFailureAfterResponseKeepsOriginalCause() throws Exception {
        try (Fixture test = new Fixture()) {
            // 响应和 EOF 已经到达，但发送结果尚未完成。
            Future<Void> sent = test.send();
            HttpResponse response = buffered();
            test.channel.received(test.socket, response, false);
            test.eof(test.socket);
            test.executor.drain();

            // 完整响应的 EOF 保护不能吞掉随后到达的真实发送错误。
            IOException failure = new IOException("Request write failed after response");
            test.socket.written.failed(failure);
            test.executor.drain();

            // 保留已收到的响应，同时使发送与交换携带原始失败。
            assertSame(response, test.exchange.response().getResult());
            assertSame(failure, sent.getCause());
            assertSame(failure, test.exchange.completion().getCause());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void eofFailsIncompleteStreamingBody() throws Exception {
        try (Fixture test = new Fixture()) {
            // 发送已经完成；响应只有部分正文，没有交付正文结束标记。
            test.send();
            test.socket.written.completed(test.socket);
            test.executor.drain();
            HttpBodyStream stream = new HttpBodyStream(32);
            stream.offer(new byte[] { 1, 2 }, () -> {
            });
            HttpResponse response = HttpResponse.newStreaming(200, Map.of(), stream, Map.of());
            test.channel.received(test.socket, response, false);
            test.eof(test.socket);
            test.executor.drain();

            // 截断响应必须同时终止交换和正文，不能被完整响应的保护分支放行。
            Throwable failure = test.exchange.completion().getCause();
            assertTrue(failure instanceof IOException);
            assertSame(failure, stream.completion().getCause());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void oldSocketEofLeavesCurrentExchangeUsable() throws Exception {
        try (Fixture test = new Fixture()) {
            // 当前请求使用新连接，旧连接的关闭通知随后才到达。
            ControlledSocket previous = test.socket;
            test.replaceSocket();
            Future<Void> sent = test.send();
            test.socket.written.completed(test.socket);
            test.executor.drain();
            test.eof(previous);
            test.executor.drain();
            assertFalse(test.exchange.completion().isDone());

            // 当前连接正常交付响应，证明旧连接的 EOF 没有污染当前交换。
            test.channel.received(test.socket, buffered(), false);
            test.executor.drain();
            assertSuccessful(sent);
            assertSuccessful(test.exchange.completion());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void eofBeforeResponseKeepsExchangeFailed() throws Exception {
        try (Fixture test = new Fixture()) {
            // 发送已经结束，但 EOF 到达时根本没有响应；随后才出现迟到的响应事件。
            test.send();
            test.socket.written.completed(test.socket);
            test.executor.drain();
            test.eof(test.socket);
            test.channel.received(test.socket, buffered(), false);
            test.executor.drain();

            // 迟到响应不能把原来缺失响应的交换恢复成成功。
            Throwable failure = test.exchange.completion().getCause();
            assertTrue(failure instanceof IOException);
            assertSame(failure, test.exchange.response().getCause());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sendCancellationAfterCompleteResponseEofAbortsPendingWrite() throws Exception {
        try (Fixture test = new Fixture()) {
            // 完整响应与 EOF 已到达，已接受写入尚未结束，物理连接正在优雅关闭。
            Future<Void> sent = test.send();
            test.channel.received(test.socket, buffered(), true);
            test.eof(test.socket);
            test.executor.drain();
            Future<NetChannel> closing = test.socket.close();
            assertFalse(test.socket.written.isDone());
            assertFalse(closing.isDone());

            // 取消公开发送必须仍能定位原连接，立即中止底层写入并释放物理连接。
            assertTrue(sent.cancel());
            test.executor.drain();
            assertTrue(sent.isCancelled());
            assertTrue(test.exchange.completion().getCause() instanceof CancellationException);
            assertTrue(test.socket.written.getCause() instanceof SoUnfinishedSndException);
            assertSuccessful(closing);
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void exchangeCancellationAfterCompleteResponseEofAbortsPendingWrite() throws Exception {
        try (Fixture test = new Fixture()) {
            // 原写入尚在等待完成通知，响应和 EOF 不得丢失物理连接的管理归属。
            Future<Void> sent = test.send();
            test.channel.received(test.socket, buffered(), true);
            test.eof(test.socket);
            test.executor.drain();
            Future<NetChannel> closing = test.socket.close();
            assertFalse(test.socket.written.isDone());
            assertFalse(closing.isDone());

            // 交换自身的取消通过原生命周期链路终止发送，并立即关闭原连接。
            assertTrue(test.exchange.completion().cancel());
            test.executor.drain();
            assertTrue(test.exchange.completion().isCancelled());
            assertTrue(sent.getCause() instanceof SoUnfinishedSndException);
            assertTrue(test.socket.written.getCause() instanceof SoUnfinishedSndException);
            assertSuccessful(closing);
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void eofWithoutResponseAbortsPendingWriteImmediately() throws Exception {
        try (Fixture test = new Fixture()) {
            // 请求已接受但写入未完成，EOF 到来时没有任何响应。
            Future<Void> sent = test.send();
            test.eof(test.socket);
            test.executor.drain();

            // 缺失响应保持原失败，物理连接立即清理而不等待写入完成通知。
            Throwable failure = test.exchange.completion().getCause();
            assertTrue(failure instanceof IOException);
            assertSame(failure, sent.getCause());
            assertTrue(test.socket.written.getCause() instanceof SoUnfinishedSndException);
            assertSuccessful(test.socket.close());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void eofWithTruncatedResponseAbortsPendingWriteImmediately() throws Exception {
        try (Fixture test = new Fixture()) {
            // 请求写入仍待完成，响应正文只到达部分数据，随后输入结束。
            Future<Void> sent = test.send();
            HttpBodyStream stream = new HttpBodyStream(32);
            stream.offer(new byte[] { 1, 2 }, () -> {
            });
            HttpResponse response = HttpResponse.newStreaming(200, Map.of(), stream, Map.of());
            test.channel.received(test.socket, response, false);
            test.eof(test.socket);
            test.executor.drain();

            // 截断正文和交换必须失败，物理写入同时被立即关闭终止。
            Throwable failure = test.exchange.completion().getCause();
            assertTrue(failure instanceof IOException);
            assertSame(failure, sent.getCause());
            assertSame(failure, stream.completion().getCause());
            assertTrue(test.socket.written.getCause() instanceof SoUnfinishedSndException);
            assertSuccessful(test.socket.close());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void inboundErrorAbortsPendingWriteImmediately() throws Exception {
        try (Fixture test = new Fixture()) {
            // 尚未收到响应时，真实输入错误先于发送完成通知到达。
            Future<Void> sent = test.send();
            IOException failure = new IOException("HTTP input failed");
            test.inputError(test.socket, failure);
            test.executor.drain();

            // 原错误保留在发送和交换中，物理连接不进入等待排空的关闭流程。
            assertSame(failure, sent.getCause());
            assertSame(failure, test.exchange.completion().getCause());
            assertTrue(test.socket.written.getCause() instanceof SoUnfinishedSndException);
            assertSuccessful(test.socket.close());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void completeResponseEofPreventsKeepAliveReuseAfterWriteFinishes() throws Exception {
        try (Fixture test = new Fixture()) {
            // 即使响应声明保持连接，随后到达的 EOF 也使该连接失去复用资格。
            Future<Void> sent = test.send();
            test.channel.received(test.socket, buffered(), true);
            test.eof(test.socket);
            test.executor.drain();
            Future<NetChannel> closing = test.socket.close();
            assertFalse(closing.isDone());

            // 原写入成功后正常完成交换，并从逻辑通道移除该物理连接。
            assertTrue(test.socket.written.completed(test.socket));
            test.executor.drain();
            assertSuccessful(sent);
            assertSuccessful(test.exchange.completion());
            assertSuccessful(closing);
            assertNull(field(Http1ClientChannel.class, "channel").get(test.channel));
        }
    }

    private static HttpResponse buffered() {
        byte[] bytes = "reply".getBytes(StandardCharsets.US_ASCII);
        return HttpResponse.newBuffered(200, Map.of(), bytes, Map.of());
    }

    private static void assertSuccessful(Future<?> future) {
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
        assertNull(future.getCause());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Fixture implements AutoCloseable {
        private final ControlledExecutor     executor = new ControlledExecutor();
        private final SocketRuntime          runtime;
        private final SoContextService       context;
        private final Http1ClientChannel     channel;
        private final HttpExchange           exchange;
        private final List<ControlledSocket> sockets  = new ArrayList<>();
        private       ControlledSocket       socket;

        private Fixture() throws Exception {
            // 保留正常构造和对象所有权，仅将串行执行器换成由测试推进的任务队列。
            InterAddress address = new InterAddress("http", "127.0.0.1", 1, "default");
            Map<String, String> options = Map.of("workerThread", "1", "taskThread", "1");
            TransportConfig config = new TransportConfig("eof-test", address, false, options, Map.of());
            ClassLoader loader = getClass().getClassLoader();
            Constructor<SocketRuntime> constructor = SocketRuntime.class.getDeclaredConstructor(//
                    TransportConfig.class, TransportPipeline.class, ClassLoader.class, SoConfig.class);
            constructor.setAccessible(true);
            this.runtime = constructor.newInstance(config, null, loader, SoConfig.TCP());
            ExecutorService[] workers = (ExecutorService[]) field(SocketRuntime.class, "workers").get(this.runtime);
            for (ExecutorService worker : workers) {
                worker.shutdownNow();
            }
            Arrays.fill(workers, this.executor);

            // 虚拟物理连接复用 Runtime 的真实 Neta context，不调用 bind、connect 或协议管线。
            NetManager network = (NetManager) field(SocketRuntime.class, "network").get(this.runtime);
            this.context = (SoContextService) network.getContext();
            this.channel = new Http1ClientChannel(this.runtime, address, null, false);
            this.replaceSocket();
            HttpRequest request = HttpRequest.newBuffered("POST", "/test", Map.of(), new byte[0], 1, Map.of());
            this.exchange = new HttpExchange(request);
        }

        private void replaceSocket() throws Exception {
            this.socket = new ControlledSocket(this.sockets.size() + 1L, this.context);
            this.sockets.add(this.socket);
            field(Http1ClientChannel.class, "channel").set(this.channel, this.socket);
        }

        private Future<Void> send() {
            // 走真实 write/enqueue/writeNext，由生产代码创建 ExchangeState 并登记 written 回调。
            Future<Void> sent = this.channel.write(this.exchange);
            this.executor.drain();
            return sent;
        }

        private void eof(ControlledSocket source) {
            PlayLoad event = (PlayLoad) Proxy.newProxyInstance(getClass().getClassLoader(), //
                    new Class<?>[] { PlayLoad.class }, (proxy, method, args) -> switch (method.getName()) {
                        case "getSource" -> source;
                        case "getData" -> SoInputCloseEvent.INSTANCE;
                        case "getError" -> null;
                        case "isSuccess", "isInbound" -> true;
                        case "isOutbound" -> false;
                        default -> throw new AssertionError("Unexpected event method: " + method.getName());
                    });
            this.channel.onInbound(event);
        }

        private void inputError(ControlledSocket source, Throwable failure) {
            PlayLoad event = (PlayLoad) Proxy.newProxyInstance(getClass().getClassLoader(), //
                    new Class<?>[] { PlayLoad.class }, (proxy, method, args) -> switch (method.getName()) {
                        case "getSource" -> source;
                        case "getError" -> failure;
                        case "isSuccess", "isOutbound" -> false;
                        case "isInbound" -> true;
                        default -> throw new AssertionError("Unexpected event method: " + method.getName());
                    });
            this.channel.onInbound(event);
        }

        @Override
        public void close() throws Exception {
            // 先结束逻辑通道，再释放持有的 Runtime 及其计时器、执行器。
            this.channel.close();
            this.executor.drain();
            for (ControlledSocket socket : this.sockets) {
                socket.closeNow();
            }
            Method close = SocketRuntime.class.getDeclaredMethod("close");
            close.setAccessible(true);
            close.invoke(this.runtime);
        }
    }

    private static final class ControlledSocket extends NetChannel {
        private final BasicFuture<NetChannel> written = new BasicFuture<>();

        private ControlledSocket(long id, SoContextService context) {
            super(id, new NetMonitor(), null, stack -> {
            }, transport(id), context);
        }

        private static AsyncChannel transport(long id) {
            SoConfig config = SoConfig.TCP();
            ClassLoader loader = Http1ClientEofTest.class.getClassLoader();
            return (AsyncChannel) Proxy.newProxyInstance(loader, new Class<?>[] { AsyncChannel.class }, //
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getChannelId" -> id;
                        case "getSoConfig" -> config;
                        case "isOpen" -> true;
                        default -> throw new AssertionError("Unexpected transport I/O: " + method.getName());
                    });
        }

        @Override
        public Future<?> sendData(Object message) {
            // 不进行真实传输，发送结果由当前场景显式完成；释放本次写出的 HTTP 报文。
            ((HttpObject) message).release();
            return this.written;
        }

        @Override
        protected void pauseTransportRead() {
        }

        @Override
        protected void resumeTransportRead() {
        }

        @Override
        public Future<NetChannel> close() {
            // 对齐 Neta 的优雅关闭：先禁止新写入，已接受写入终结后才释放连接。
            if (this.closeStatus.compareAndSet(false, true)) {
                this.written.onFinal(done -> this.closeFuture.completed(this));
            }
            return this.closeFuture;
        }

        @Override
        public void closeNow() {
            // 对齐 Neta 的立即关闭：清理队列会使尚未结束的原发送 Future 失败。
            this.closeStatus.set(true);
            this.written.failed(new SoUnfinishedSndException("channel closed before pending data was sent."));
            this.closeFuture.completed(this);
        }
    }

    private static final class ControlledExecutor extends AbstractExecutorService {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private       boolean         shutdown;

        @Override
        public void execute(Runnable task) {
            if (this.shutdown) {
                throw new IllegalStateException("Test executor is shut down");
            }
            this.tasks.addLast(task);
        }

        private void drain() {
            while (!this.tasks.isEmpty()) {
                this.tasks.removeFirst().run();
            }
        }

        @Override
        public void shutdown() {
            this.shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            this.shutdown = true;
            List<Runnable> pending = new ArrayList<>(this.tasks);
            this.tasks.clear();
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return this.shutdown;
        }

        @Override
        public boolean isTerminated() {
            return this.shutdown && this.tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return this.isTerminated();
        }
    }
}
