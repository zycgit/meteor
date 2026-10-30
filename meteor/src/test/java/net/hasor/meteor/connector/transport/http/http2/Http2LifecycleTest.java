/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http2;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.connector.transport.SocketRuntime;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;
import org.junit.Test;
import static net.hasor.meteor.connector.transport.TransportTestServices.closeSocketRuntime;
import static net.hasor.meteor.connector.transport.TransportTestServices.newSocketRuntime;
import static org.junit.Assert.*;

/** 明确控制发送完成和输入 EOF 的顺序，验证完整正文的消费归属及失败传播。 */
public class Http2LifecycleTest {
    @Test(timeout = 15000)
    public void completeNetworkBodyAndTrailersSurviveEofBeforeConsumption() throws Exception {
        try (Fixture test = new Fixture()) {
            // 原请求已写完，完整响应正文和尾部在应用开始消费前到达。
            Future<Void> sent = test.send();
            test.socket.written.completed(test.socket);
            test.drain();
            HttpBodyStream body = test.response(true);
            assertTrue(body.inputEnded());
            assertFalse(body.completion().isDone());
            test.eof();

            // 正常 EOF 保留缓冲及尾部，交换仍由应用消费到正文 EOF 后完成。
            assertFalse(test.exchange.completion().isDone());
            assertArrayEquals(new byte[] { 10, 20, 30 }, body.readAllBytes());
            assertEquals(Map.of("grpc-status", List.of("0")), body.trailers());
            test.drain();
            assertSuccess(sent);
            assertSuccess(body.completion());
            assertSuccess(test.exchange.completion());
            assertEquals(1, test.released.get());
        }
    }

    @Test(timeout = 15000)
    public void truncatedBodyFailsOnTransportEof() throws Exception {
        try (Fixture test = new Fixture()) {
            // 只有部分正文到达，尚未收到 HTTP/2 END_STREAM 及尾部。
            test.send();
            test.socket.written.completed(test.socket);
            test.drain();
            HttpBodyStream body = test.response(false);
            test.eof();

            // 截断必须使交换和正文失败，同时释放接收额度。
            assertTrue(test.exchange.completion().getCause() instanceof IOException);
            assertThrows(IOException.class, body::readAllBytes);
            assertTrue(body.completion().getCause() instanceof IOException);
            assertEquals(1, test.released.get());
        }
    }

    @Test(timeout = 15000)
    public void requestWriteFailureBeforeEofKeepsOriginalCause() throws Exception {
        try (Fixture test = new Fixture()) {
            // 响应已完整到达，但原请求的物理写入在连接关闭前失败。
            Future<Void> sent = test.send();
            HttpBodyStream body = test.response(true);
            IOException failure = new IOException("physical request write failed");
            test.socket.written.failed(failure);
            test.drain();
            test.eof();

            // 交换和正文保留原始写入错误，不能因响应完整而变成成功。
            assertSame(failure, sent.getCause());
            assertSame(failure, test.exchange.completion().getCause());
            assertSame(failure, body.completion().getCause());
            assertThrows(IOException.class, body::readAllBytes);
        }
    }

    @Test(timeout = 15000)
    public void lateRequestWriteFailureAfterEofStillFailsUnconsumedBody() throws Exception {
        try (Fixture test = new Fixture()) {
            // 响应正文已结束并收到 EOF，原请求的物理发送回调仍未完成。
            Future<Void> sent = test.send();
            HttpBodyStream body = test.response(true);
            test.eof();
            assertFalse(sent.isDone());

            // 保留输入不能提前完成交换；随后到达的真实写入错误仍须终止未消费正文。
            IOException failure = new IOException("late physical request write failed");
            test.socket.written.failed(failure);
            test.drain();
            assertSame(failure, sent.getCause());
            assertSame(failure, test.exchange.completion().getCause());
            assertSame(failure, body.completion().getCause());
            assertThrows(IOException.class, body::readAllBytes);
        }
    }

    @Test(timeout = 15000)
    public void sendCancellationAfterEofStillFailsUnconsumedBody() throws Exception {
        try (Fixture test = new Fixture()) {
            // EOF 已到达，调用方仍持有尚未完成的发送 Future。
            Future<Void> sent = test.send();
            HttpBodyStream body = test.response(true);
            test.eof();
            assertTrue(sent.cancel());
            test.drain();

            // 取消继续沿交换传播到正文，完整输入不能覆盖调用方的取消。
            assertTrue(sent.isCancelled());
            Throwable cancellation = test.exchange.completion().getCause();
            assertTrue(cancellation instanceof CancellationException);
            assertSame(cancellation, body.completion().getCause().getCause());
            assertThrows(IOException.class, body::readAllBytes);
        }
    }

    @Test(timeout = 15000)
    public void exchangeCancellationBeforeEofIsNotRevived() throws Exception {
        try (Fixture test = new Fixture()) {
            // 响应已完整到达，在物理连接关闭前主动取消交换。
            test.send();
            HttpBodyStream body = test.response(true);
            CancellationException cancelled = new CancellationException("caller cancelled exchange");
            test.exchange.complete(cancelled);
            test.drain();
            test.eof();

            // EOF 之后仍保留原取消原因和正文失败状态。
            assertSame(cancelled, test.exchange.completion().getCause());
            assertThrows(IOException.class, body::readAllBytes);
            assertSame(cancelled, body.completion().getCause().getCause());
        }
    }

    @Test(timeout = 15000)
    public void inputFailureAfterBodyFinishDoesNotReviveFailedBody() throws Exception {
        try (Fixture test = new Fixture()) {
            // 已收到 END_STREAM 的正文发生完整性错误，先将错误传递给交换。
            test.send();
            HttpBodyStream body = test.response(true);
            IOException failure = new IOException("body integrity failure");
            body.fail(failure);
            test.drain();
            test.eof();

            // 连接关闭不能因为 inputEnded 标记而恢复已有失败。
            assertSame(failure, body.completion().getCause());
            assertSame(failure, test.exchange.completion().getCause());
            assertThrows(IOException.class, body::readAllBytes);
        }
    }

    @Test(timeout = 15000)
    public void explicitTransportFailureDoesNotPreserveFinishedResponse() throws Exception {
        try (Fixture test = new Fixture()) {
            // 正文已完整到达，随后传输层报告明确的协议错误。
            test.send();
            HttpBodyStream body = test.response(true);
            IOException failure = new IOException("transport protocol failure");
            test.channel.fail(failure);
            test.drain();

            // 完整输入保护只适用于正常 EOF，明确的传输错误仍须传播。
            assertSame(failure, test.exchange.completion().getCause());
            assertSame(failure, body.completion().getCause());
            assertThrows(IOException.class, body::readAllBytes);
        }
    }

    @Test(timeout = 15000)
    public void missingResponseFailsOnEof() throws Exception {
        try (Fixture test = new Fixture()) {
            // 请求已写完，但连接关闭前没有收到任何响应头。
            test.send();
            test.socket.written.completed(test.socket);
            test.drain();
            test.eof();

            // 响应缺失仍是失败，交换和响应 Future 使用同一个错误。
            assertTrue(test.exchange.completion().getCause() instanceof IOException);
            assertSame(test.exchange.completion().getCause(), test.exchange.response().getCause());
        }
    }

    private static void assertSuccess(Future<?> value) {
        assertTrue(value.isDone());
        assertNull(value.getCause());
        assertFalse(value.isCancelled());
    }

    private static final class Fixture implements AutoCloseable {
        private final SocketRuntime    runtime;
        private final NetManager       sockets;
        private final ControlledSocket socket;
        private final AtomicInteger    released = new AtomicInteger();
        private final Http2Channel     channel;
        private final HttpExchange     exchange;

        private Fixture() throws Exception {
            // 复用现有测试桥接创建 Runtime，受控连接只借用测试自己持有的网络上下文。
            ClassLoader loader = this.getClass().getClassLoader();
            InterAddress remote = new InterAddress("http", "127.0.0.1", 12345, "default");
            TransportConfig config = new TransportConfig("http2-lifecycle", remote, false, //
                    Map.of("workerThread", "1", "taskThread", "1", "httpVersion", "2"), Map.of());
            this.runtime = newSocketRuntime(config, loader);
            NetConfig networking = new NetConfig();
            networking.setClassLoader(loader);
            networking.setIoThreads(1);
            networking.setTaskThreads(1);
            networking.setPrintLog(false);
            this.sockets = new NetManager(networking);
            this.socket = new ControlledSocket((SoContextService) this.sockets.getContext());

            // 只替换物理发送完成信号，通道、正文及交换都使用真实实现。
            this.channel = new Http2Channel(this.runtime, this.socket, remote, null, true);
            this.channel.initialize(connection -> new ChannelListener<>() {
                @Override
                public void connected() {
                }

                @Override
                public void receive(HttpExchange incoming) {
                    throw new AssertionError("Unexpected inbound request");
                }

                @Override
                public void closed(Throwable failure) {
                    // 当前测试只验证传输层消费归属，不模拟上层协议会话的关闭逻辑。
                }
            });
            HttpRequest request = HttpRequest.newBuffered("POST", "/Echo/echo", Map.of(), new byte[0], 2, Map.of());
            this.exchange = new HttpExchange(request);
        }

        private Future<Void> send() throws Exception {
            // 让真实 write 路径建立 stream 与交换的关联，然后等待发送回调的显式推进。
            Future<Void> sent = this.channel.write(this.exchange);
            this.drain();
            return sent;
        }

        private HttpBodyStream response(boolean finished) throws Exception {
            // 将接收块交给真实正文缓冲，finish 同时提交 END_STREAM 和尾部字段。
            HttpBodyStream body = new HttpBodyStream(1024);
            body.offer(new byte[] { 10, 20, 30 }, this.released::incrementAndGet);
            if (finished) {
                body.finish(Map.of("grpc-status", List.of("0")));
            }
            HttpResponse response = HttpResponse.newStreaming(200, Map.of(), body, Map.of());
            this.channel.receiveResponse(1, response);
            this.drain();
            assertSame(response, this.exchange.response().getResult());
            return body;
        }

        private void eof() throws Exception {
            this.channel.disconnected();
            this.drain();
        }

        private void drain() throws Exception {
            // 先完成直接任务，再完成这些任务投递到同一串行执行器的生命周期回调。
            for (int phase = 0; phase < 2; phase++) {
                CountDownLatch barrier = new CountDownLatch(1);
                this.channel.execute(barrier::countDown);
                assertTrue("Ordered executor did not reach barrier", barrier.await(3, TimeUnit.SECONDS));
            }
        }

        @Override
        public void close() throws Exception {
            // 先结束当前连接，再释放测试自己创建并持有的 Runtime 和网络上下文。
            try {
                this.socket.closeNow();
                this.channel.disconnected();
                this.drain();
            } finally {
                try {
                    closeSocketRuntime(this.runtime);
                } finally {
                    this.sockets.shutdown();
                }
            }
        }
    }

    private static final class ControlledSocket extends NetChannel {
        private final BasicFuture<NetChannel> written = new BasicFuture<>();

        private ControlledSocket(SoContextService context) {
            super(1, new NetMonitor(), null, stack -> {
            }, transport(), context);
        }

        private static AsyncChannel transport() {
            ClassLoader loader = Http2LifecycleTest.class.getClassLoader();
            return (AsyncChannel) Proxy.newProxyInstance(loader, new Class<?>[] { AsyncChannel.class }, //
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getChannelId" -> 1L;
                        case "getSoConfig" -> SoConfig.TCP();
                        case "isOpen" -> true;
                        default -> throw new AssertionError("Unexpected physical I/O: " + method.getName());
                    });
        }

        @Override
        public Future<?> sendData(Object value) {
            // 测试显式完成原发送 Future；本次写出的报文仍由发送所有者释放。
            ((HttpObject) value).release();
            return this.written;
        }

        @Override
        public <T> void fireEvent(Class<T> type, T event) {
            // 这里只观察本地生命周期，向外发送的 RST 不反向修改真实交换和正文。
        }

        @Override
        protected void pauseTransportRead() {
        }

        @Override
        protected void resumeTransportRead() {
        }

        @Override
        public void closeNow() {
            // 与立即关闭一致，使尚未完成的物理发送失败并释放连接。
            this.closeStatus.set(true);
            this.written.failed(new SoUnfinishedSndException("pending request send closed"));
            this.closeFuture.completed(this);
        }
    }
}
