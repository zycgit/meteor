/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.grpc.codec.GrpcWebCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcWebChannelTest {
    @Test
    public void inboundReplyUsesTheOriginalPhysicalExchange() throws Exception {
        // Web 请求与 native 视图通过适配器关联。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange wire = new HttpExchange(this.webRequest());
        List<HttpExchange> incoming = new ArrayList<>();
        channel.receive(wire, incoming::add);
        HttpExchange nativeExchange = incoming.get(0);

        // native 响应必须回到原来的网络交换，不创建第二个请求。
        nativeExchange.respond(this.nativeResponse());
        Future<Void> sent = channel.write(nativeExchange);
        assertEquals(List.of(wire), network.writes);
        assertTrue(wire.response().isDone());
        assertFalse(sent.isDone());
        network.writing.completed(null);
        sent.get(2, TimeUnit.SECONDS);
        channel.closed(new IOException("test complete"));
    }

    @Test
    public void physicalInboundFailureEndsItsNativeView() throws Exception {
        // 已接收请求，但连接在业务响应前失败。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange wire = new HttpExchange(this.webRequest());
        List<HttpExchange> incoming = new ArrayList<>();
        channel.receive(wire, incoming::add);

        // 失败同步到 native 交换，从而结束 Caller/Dispatcher 的请求。
        IOException failure = new IOException("peer disconnected");
        wire.complete(failure);
        assertSame(failure, incoming.get(0).completion().getCause());
        assertTrue(network.writes.isEmpty());
        channel.closed(failure);
    }

    @Test
    public void nativeCancellationEndsTheInboundPhysicalExchange() throws Exception {
        // 业务请求完成前由上层取消。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange wire = new HttpExchange(this.webRequest());
        List<HttpExchange> incoming = new ArrayList<>();
        channel.receive(wire, incoming::add);

        // 取消只能结束本次交换，不能关闭物理连接。
        CancellationException failure = new CancellationException("cancelled by caller");
        incoming.get(0).complete(failure);
        assertSame(failure, wire.completion().getCause());
        assertEquals(0, network.closed);
        channel.closed(failure);
    }

    @Test
    public void outboundWriteCreatesAWebExchange() throws Exception {
        // native 视图始终使用 HTTP/2，实际网络版本由 Web 挂载配置决定。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange request = new HttpExchange(this.nativeRequest());
        channel.write(request);

        // 请求转换不修改 native 请求对象，也不复用它作为网络交换。
        HttpExchange wire = network.writes.get(0);
        assertNotSame(request, wire);
        assertEquals(1, wire.request().version());
        assertEquals(2, request.request().version());
        assertEquals("application/grpc-web+json", wire.request().header("content-type"));
        channel.closed(new IOException("test complete"));
    }

    @Test
    public void responseCanArriveBeforeRequestWriteCompletes() throws Exception {
        // 请求已交给网络但写 Future 尚未完成。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange request = new HttpExchange(this.nativeRequest());
        Future<Void> sent = channel.write(request);
        HttpExchange wire = network.writes.get(0);

        // 提前到达的响应要交给 native 调用，不能被未完成的写入覆盖。
        wire.respond(this.webResponse());
        HttpResponse response = request.response().get(2, TimeUnit.SECONDS);
        assertEquals(200, response.status());
        assertFalse(sent.isDone());
        network.writing.completed(null);
        sent.get(2, TimeUnit.SECONDS);
        channel.closed(new IOException("test complete"));
    }

    @Test
    public void outgoingWriteFailureTerminatesBothExchanges() throws Exception {
        // 网络提交失败发生在任何响应之前。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange request = new HttpExchange(this.nativeRequest());
        Future<Void> sent = channel.write(request);
        HttpExchange wire = network.writes.get(0);

        // 原始失败必须同时抵达写调用方、native 请求和物理交换。
        IOException failure = new IOException("write rejected");
        network.writing.failed(failure);
        assertSame(failure, sent.getCause());
        assertSame(failure, request.completion().getCause());
        assertSame(failure, wire.completion().getCause());
        channel.closed(failure);
    }

    @Test
    public void cancellingOutgoingWriteTerminatesItsRequest() throws Exception {
        // write 返回的 Future 仍处于等待物理写入完成状态。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange request = new HttpExchange(this.nativeRequest());
        Future<Void> sent = channel.write(request);
        HttpExchange wire = network.writes.get(0);

        // 取消写入必须释放两侧交换，不能等待响应永远挂起。
        sent.cancel();
        assertTrue(sent.isCancelled());
        assertTrue(request.completion().getCause() instanceof CancellationException);
        assertTrue(wire.completion().getCause() instanceof CancellationException);
        channel.closed(new IOException("test complete"));
    }

    @Test
    public void outgoingRpcCancellationCancelsPhysicalExchange() throws Exception {
        // Caller 在网络响应之前结束 native 请求。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange request = new HttpExchange(this.nativeRequest());
        channel.write(request);
        HttpExchange wire = network.writes.get(0);

        // 取消沿关联传给网络交换，不触碰借用的网络通道。
        CancellationException failure = new CancellationException("RPC deadline expired");
        request.complete(failure);
        assertSame(failure, wire.completion().getCause());
        assertEquals(0, network.closed);
        channel.closed(failure);
    }

    @Test
    public void physicalResponseFailureReachesTheNativeCaller() throws Exception {
        // 请求写出后，物理交换因对端断开失败。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange request = new HttpExchange(this.nativeRequest());
        channel.write(request);
        IOException failure = new IOException("response disconnected");

        // native 响应 Future 保留相同的传输错误。
        network.writes.get(0).complete(failure);
        assertSame(failure, request.response().getCause());
        assertSame(failure, request.completion().getCause());
        channel.closed(failure);
    }

    @Test
    public void closingAdapterEndsOutstandingOutgoingExchange() throws Exception {
        // 适配器关闭时仍有未收到响应的请求。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange request = new HttpExchange(this.nativeRequest());
        channel.write(request);
        HttpExchange wire = network.writes.get(0);

        // 释放自己管理的交换，保持底层连接所有权不变。
        IOException failure = new IOException("protocol closed");
        channel.closed(failure);
        assertSame(failure, request.completion().getCause());
        assertSame(failure, wire.completion().getCause());
        assertEquals(0, network.closed);
    }

    @Test
    public void closingAdapterEndsOutstandingIncomingExchange() throws Exception {
        // 入站业务尚未提交响应时关闭协议适配。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange wire = new HttpExchange(this.webRequest());
        List<HttpExchange> incoming = new ArrayList<>();
        channel.receive(wire, incoming::add);
        IOException failure = new IOException("protocol closed");

        // 两侧交换都结束，但连接由创建者关闭。
        channel.closed(failure);
        assertSame(failure, wire.completion().getCause());
        assertSame(failure, incoming.get(0).completion().getCause());
        assertEquals(0, network.closed);
    }

    @Test
    public void closedAdapterRejectsNewOutgoingWrite() throws Exception {
        // 已关闭的适配器不能再申请物理交换。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        IOException failure = new IOException("protocol closed");
        channel.closed(failure);

        // 失败返回给原始请求，网络没有新增写入。
        HttpExchange request = new HttpExchange(this.nativeRequest());
        Future<Void> sent = channel.write(request);
        assertSame(failure, sent.getCause());
        assertSame(failure, request.completion().getCause());
        assertTrue(network.writes.isEmpty());
    }

    @Test
    public void closedAdapterRejectsNewIncomingExchange() throws Exception {
        // 连接关闭通知与已排队的接收回调可能交错。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        IOException failure = new IOException("protocol closed");
        channel.closed(failure);
        HttpExchange wire = new HttpExchange(this.webRequest());
        List<HttpExchange> incoming = new ArrayList<>();

        // 迟到请求结束自身，不得重新投递业务。
        channel.receive(wire, incoming::add);
        assertTrue(incoming.isEmpty());
        assertSame(failure, wire.completion().getCause());
    }

    @Test
    public void incomingWorkerRejectionTerminatesTheBody() throws Exception {
        // 使用增量正文进入工作线程处理，模拟线程池已满。
        ControlledNetwork network = new ControlledNetwork();
        RejectedExecutionException failure = new RejectedExecutionException("workers saturated");
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, command -> {
            throw failure;
        });
        HttpBodyStream body = new HttpBodyStream(64);
        HttpRequest buffered = this.webRequest();
        HttpRequest request = HttpRequest.newStreaming("POST", buffered.uri(), buffered.headers(), body, 1);
        HttpExchange wire = new HttpExchange(request);
        List<HttpExchange> incoming = new ArrayList<>();

        // 无法开始解码时必须释放上游正文，也不能投递未解析的请求。
        channel.receive(wire, incoming::add);
        assertTrue(incoming.isEmpty());
        assertTrue(wire.completion().isDone());
        assertTrue(body.completion().isDone());
        channel.closed(failure);
    }

    @Test
    public void outgoingResponseWorkerRejectionTerminatesTheCall() throws Exception {
        // 写请求使用完整正文，响应采用流式正文触发被拒绝的解码任务。
        ControlledNetwork network = new ControlledNetwork();
        RejectedExecutionException failure = new RejectedExecutionException("workers saturated");
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, command -> {
            throw failure;
        });
        HttpExchange request = new HttpExchange(this.nativeRequest());
        channel.write(request);
        HttpBodyStream body = new HttpBodyStream(64);
        HttpResponse response = HttpResponse.newStreaming(200, this.webHeaders(), body, Map.of());

        // 工作拒绝不能留下一个永远不结束的响应流。
        network.writes.get(0).respond(response);
        assertTrue(request.completion().isDone());
        assertTrue(body.completion().isDone());
        channel.closed(failure);
    }

    @Test
    public void adapterCloseDoesNotCloseBorrowedNetwork() {
        // 适配层仅借用网络通道，close 与 drain 不能越过所有权边界。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        channel.close();
        channel.drainAndClose();
        assertEquals(0, network.closed);
        assertEquals(0, network.drained);
    }

    @Test
    public void queuedResponseSurvivesSuccessfulPhysicalCompletion() throws Exception {
        // 真实连接会把协议回调放入有序队列，网络 EOF 可能先到。
        ControlledNetwork network = new ControlledNetwork();
        network.queueTasks = true;
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange request = new HttpExchange(this.nativeRequest());
        channel.write(request);
        HttpExchange wire = network.writes.get(0);
        wire.respond(this.webResponse());
        wire.complete(null);
        assertFalse(request.response().isDone());

        // 推进协议回调后仍然交付响应，成功 EOF 不能抢先终止 native 请求。
        network.runTasks();
        HttpResponse response = request.response().get(2, TimeUnit.SECONDS);
        assertArrayEquals(this.frame("result"), response.stream().readAllBytes());
        assertEquals(List.of("0"), response.stream().trailers().get("grpc-status"));
        channel.closed(new IOException("test complete"));
    }

    @Test
    public void closeRetainsOwnershipOfConversionAfterPhysicalEof() throws Exception {
        // 物理响应完整，但转码 worker 还没消费正文。
        ControlledNetwork network = new ControlledNetwork();
        network.queueTasks = true;
        Queue<Runnable> workers = new ArrayDeque<>();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, workers::add);
        HttpExchange request = new HttpExchange(this.nativeRequest());
        channel.write(request);
        HttpExchange wire = network.writes.get(0);
        wire.respond(this.webResponse());
        wire.complete(null);
        network.runTasks();
        HttpResponse response = request.response().get(2, TimeUnit.SECONDS);

        // 转码中的交换仍属于适配器，close 必须结束等待中的正文。
        IOException failure = new IOException("close during response conversion");
        channel.closed(failure);
        assertSame(failure, request.completion().getCause());
        assertSame(failure, response.stream().completion().getCause());
        assertEquals(0, network.closed);
    }

    @Test
    public void inboundDeadlineBeforeResponseWritesDeadlineTrailer() throws Exception {
        // 业务尚未产生响应，native 调用先收到截止事件。
        ControlledNetwork network = new ControlledNetwork();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, Runnable::run);
        HttpExchange wire = new HttpExchange(this.webRequest());
        List<HttpExchange> incoming = new ArrayList<>();
        channel.receive(wire, incoming::add);
        MetTimeoutException timeout = new MetTimeoutException("business deadline");
        incoming.get(0).complete(timeout);

        // 截止通过一条标准尾帧返回，物理交换仍等待网络实际写完。
        assertEquals(List.of(wire), network.writes);
        assertFalse(wire.completion().isDone());
        HttpResponse response = wire.response().get(2, TimeUnit.SECONDS);
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        GrpcWebCodec codec = new GrpcWebCodec(this.config());
        Map<String, List<String>> trailers = codec.decodeResponse( //
                new ByteArrayInputStream(response.body()), frames, false, "identity");
        assertEquals(List.of("4"), trailers.get("grpc-status"));
        assertEquals(0, frames.size());
        assertSame(timeout, incoming.get(0).completion().getCause());
        assertEquals(0, network.closed);
        wire.complete(null);
        channel.closed(new IOException("test complete"));
    }

    @Test
    public void streamingDeadlinePreservesDataAndAppendsDeadlineTrailer() throws Exception {
        // 一个 worker 转码，另一个模拟网络消费，避免测试本身堵塞正文。
        ControlledNetwork network = new ControlledNetwork();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, workers);
        try {
            HttpExchange wire = new HttpExchange(this.webRequest());
            List<HttpExchange> incoming = new ArrayList<>();
            channel.receive(wire, incoming::add);
            HttpExchange nativeExchange = incoming.get(0);
            HttpBodyStream source = new HttpBodyStream(64);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/grpc+json"));
            nativeExchange.respond(HttpResponse.newStreaming(200, headers, source, Map.of()));
            channel.write(nativeExchange);
            HttpBodyStream target = wire.response().get(2, TimeUnit.SECONDS).stream();
            byte[] frame = this.frame("first");
            source.output().write(frame);
            FutureTask<byte[]> first = new FutureTask<>(() -> target.readNBytes(frame.length));
            workers.execute(first);
            byte[] written = first.get(2, TimeUnit.SECONDS);
            assertArrayEquals(frame, written);

            // 完整消息已经送达后截止，协议应保留它并用状态 4 结束正文。
            MetTimeoutException timeout = new MetTimeoutException("stream deadline");
            nativeExchange.complete(timeout);
            FutureTask<byte[]> ending = new FutureTask<>(target::readAllBytes);
            workers.execute(ending);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(written);
            body.write(ending.get(2, TimeUnit.SECONDS));
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            GrpcWebCodec codec = new GrpcWebCodec(this.config());
            Map<String, List<String>> trailers = codec.decodeResponse( //
                    new ByteArrayInputStream(body.toByteArray()), decoded, false, "identity");
            assertArrayEquals(frame, decoded.toByteArray());
            assertEquals(List.of("4"), trailers.get("grpc-status"));
            assertSame(timeout, nativeExchange.completion().getCause());
            assertFalse(wire.completion().isDone());
            assertEquals(0, network.closed);
            wire.complete(null);
        } finally {
            channel.closed(new IOException("test complete"));
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void physicalWriteFailureAfterNativeDeadlineStillFailsWebBody() throws Exception {
        // native 已截止，但错误尾帧的转码任务尚未执行。
        ControlledNetwork network = new ControlledNetwork();
        Queue<Runnable> workers = new ArrayDeque<>();
        GrpcWebChannel channel = new GrpcWebChannel(this.config(), network, workers::add);
        HttpExchange wire = new HttpExchange(this.webRequest());
        List<HttpExchange> incoming = new ArrayList<>();
        channel.receive(wire, incoming::add);
        HttpExchange nativeExchange = incoming.get(0);
        HttpBodyStream source = new HttpBodyStream(64);
        Map<String, List<String>> headers = Map.of("content-type", List.of("application/grpc+json"));
        nativeExchange.respond(HttpResponse.newStreaming(200, headers, source, Map.of()));
        channel.write(nativeExchange);
        HttpBodyStream target = wire.response().get(2, TimeUnit.SECONDS).stream();
        MetTimeoutException timeout = new MetTimeoutException("stream deadline");
        nativeExchange.complete(timeout);

        // 后到的物理写失败仍需释放 Web 交换与输出流，不能被 native 的首次失败遮蔽。
        IOException failure = new IOException("network write failed after deadline");
        network.writing.failed(failure);
        assertSame(timeout, nativeExchange.completion().getCause());
        assertSame(failure, wire.completion().getCause());
        assertSame(failure, target.completion().getCause());
        workers.remove().run();
        assertEquals(0, network.closed);
        channel.closed(failure);
    }

    private ProtocolConfig config() {
        return ProtocolConfig.newConfig("grpc-web", "grpc-web", Map.of("contextPath", "/web", "httpVersion", "1"));
    }

    private HttpRequest nativeRequest() {
        Map<String, List<String>> headers = Map.of("content-type", List.of("application/grpc+json"));
        return HttpRequest.newBuffered("POST", "/web/example.Echo/echo", headers, this.frame("request"), 2, Map.of());
    }

    private HttpRequest webRequest() throws IOException {
        byte[] body = new GrpcWebCodec(this.config()).encodeRequest(this.frame("request"), false);
        return HttpRequest.newBuffered("POST", "/web/example.Echo/echo", this.webHeaders(), body, 1, Map.of());
    }

    private HttpResponse nativeResponse() {
        Map<String, List<String>> headers = Map.of("content-type", List.of("application/grpc+json"));
        return HttpResponse.newBuffered(200, headers, this.frame("result"), Map.of("grpc-status", List.of("0")));
    }

    private HttpResponse webResponse() throws IOException {
        byte[] body = new GrpcWebCodec(this.config()).encodeResponse( //
                this.frame("result"), Map.of("grpc-status", List.of("0")), false);
        return HttpResponse.newBuffered(200, this.webHeaders(), body, Map.of());
    }

    private Map<String, List<String>> webHeaders() {
        return Map.of("content-type", List.of("application/grpc-web+json"));
    }

    private byte[] frame(String value) {
        byte[] message = ("\"" + value + "\"").getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[message.length + 5];
        frame[4] = (byte) message.length;
        System.arraycopy(message, 0, frame, 5, message.length);
        return frame;
    }

    private static final class ControlledNetwork implements NetworkChannel<HttpExchange> {
        private final List<HttpExchange> writes  = new ArrayList<>();
        private final BasicFuture<Void>  writing = new BasicFuture<>();
        private       int                closed;
        private       int                drained;
        private       boolean            queueTasks;
        private final Queue<Runnable>    tasks   = new ArrayDeque<>();

        public InterAddress getLocal() {
            return new InterAddress("grpc-web", "127.0.0.1", 2181, "default");
        }

        public InterAddress getRemote() {
            return this.getLocal();
        }

        public boolean isOpen() {
            return this.closed == 0;
        }

        public Future<Void> write(HttpExchange exchange) {
            this.writes.add(exchange);
            return this.writing;
        }

        public void execute(Runnable task) {
            if (this.queueTasks) {
                this.tasks.add(task);
            } else {
                task.run();
            }
        }

        private void runTasks() {
            while (!this.tasks.isEmpty()) {
                this.tasks.remove().run();
            }
        }

        public Future<Void> close() {
            this.closed++;
            return new BasicFuture<>((Void) null);
        }

        public Future<Void> drainAndClose() {
            this.drained++;
            return new BasicFuture<>((Void) null);
        }
    }
}
