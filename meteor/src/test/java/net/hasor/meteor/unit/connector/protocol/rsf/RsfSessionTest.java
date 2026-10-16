/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.protocol.rsf;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.ProtocolTestAccess;
import net.hasor.meteor.connector.protocol.rsf.TestRsfSession;
import net.hasor.meteor.connector.protocol.rsf.codec.RsfV1Codec;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.OptionInfo;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import org.junit.Test;
import static net.hasor.meteor.unit.connector.protocol.rsf.support.RsfFixtures.fixture;
import static net.hasor.meteor.unit.connector.protocol.rsf.support.RsfFixtures.serializationContext;
import static org.junit.Assert.*;

/** 使用可控时钟和内存通道验证会话状态，不代表用户网络调用。 */
public class RsfSessionTest {
    private final Context    context = new Context();
    private final RsfV1Codec codec   = new RsfV1Codec(this.context.runtime);

    @Test
    public void fragmentedHandshakeCompletesOnlyAfterTheLastByte() throws Exception {
        // 建立等待握手的会话，每次只交付一个字节。
        Connection connection = new Connection();
        List<OptionInfo> received = new ArrayList<>();
        ProtocolSession<byte[]> session = this.session(connection, received, Collections.emptyMap());
        session.connected();
        assertFalse(session.ready().isDone());
        byte[] hello = fixture("handshake-v1.bin");

        // 完整帧到达前保持未就绪，最终完成并撤销超时。
        for (int i = 0; i < hello.length - 1; i++) {
            session.receive(new byte[] { hello[i] });
            assertFalse(session.ready().isDone());
        }
        session.receive(new byte[] { hello[hello.length - 1] });
        assertTrue(session.ready().isDone());
        assertTrue(this.context.cancelled);
        assertTrue(received.isEmpty());
    }

    @Test
    public void coalescedApplicationMessagesHaveIndependentFrames() throws Exception {
        // 完成握手，并准备请求和响应合并在一次接收中的字节流。
        Connection connection = new Connection();
        List<OptionInfo> received = new ArrayList<>();
        ProtocolSession<byte[]> session = this.session(connection, received, Collections.emptyMap());
        session.connected();
        session.receive(fixture("handshake-v1.bin"));
        RequestPayload request = (RequestPayload) this.codec.decode(fixture("request-v1.bin"));
        session.send(request).get(2, TimeUnit.SECONDS);
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.write(fixture("request-v1.bin"));
        joined.write(fixture("response-v1.bin"));

        // 粘包中的两个报文独立分发，响应仍匹配之前发送的请求。
        session.receive(joined.toByteArray());
        assertEquals(2, received.size());
        assertTrue(received.get(0) instanceof RequestPayload);
        assertTrue(received.get(1) instanceof ResponsePayload);
    }

    @Test
    public void requestsCannotBeSentBeforeHandshake() throws Exception {
        // 会话尚未收到对端握手。
        Connection connection = new Connection();
        ProtocolSession<byte[]> session = this.session(connection, new ArrayList<>(), Collections.emptyMap());
        session.connected();
        RequestPayload request = new RequestPayload();
        request.setRequestID(1);

        // 业务请求失败且通道只写出本端握手。
        assertNotNull(session.send(request).getCause());
        assertEquals(1, connection.writes.size());
        assertTrue(connection.open);
    }

    @Test
    public void handshakeTimeoutClosesTransport() throws Exception {
        // 对端一直未发送握手，以可控定时器触发期限。
        Connection connection = new Connection();
        ProtocolSession<byte[]> session = this.session(connection, new ArrayList<>(), Collections.emptyMap());
        session.connected();

        // 超时必须关闭通道，并通过 ready future 报告原因。
        this.context.task.run();
        assertFalse(connection.open);
        assertTrue(session.ready().getCause() instanceof TimeoutException);
    }

    @Test
    public void oversizedFrameIsRejectedBeforeDispatch() throws Exception {
        // 长度字段声明的帧大小超过配置上限。
        List<OptionInfo> received = new ArrayList<>();
        Map<String, String> options = Collections.singletonMap("maxFrameSize", "1024");
        ProtocolSession<byte[]> session = this.session(new Connection(), received, options);
        session.connected();
        byte[] invalid = Arrays.copyOf(fixture("handshake-v1.bin"), 13);
        invalid[10] = 0x7F;

        // 在分发报文前拒绝非法长度。
        assertThrows(IOException.class, () -> session.receive(invalid));
        assertTrue(received.isEmpty());
    }

    @Test
    public void unsupportedFrameIsRejectedBeforeDispatch() throws Exception {
        // 将握手头替换为不支持的 RSF 版本。
        List<OptionInfo> received = new ArrayList<>();
        ProtocolSession<byte[]> session = this.session(new Connection(), received, Collections.emptyMap());
        session.connected();
        byte[] invalid = fixture("handshake-v1.bin");
        invalid[0] = (byte) 0xB2;

        // 未识别的头不能进入业务分发。
        assertThrows(IOException.class, () -> session.receive(invalid));
        assertTrue(received.isEmpty());
    }

    @Test
    public void applicationFrameBeforeHandshakeIsRejected() throws Exception {
        // 准备尚未完成握手的会话，记录是否发生业务分发。
        Connection connection = new Connection();
        List<OptionInfo> received = new ArrayList<>();
        ProtocolSession<byte[]> session = this.session(connection, received, Collections.emptyMap());
        session.connected();

        // 应用帧不能代替握手，失败后也不能被分发。
        try {
            session.receive(fixture("request-v1.bin"));
            fail("Application data cannot complete the handshake");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Expected RSF handshake"));
        }
        assertFalse(session.ready().isDone());
        assertTrue(received.isEmpty());
    }

    @Test
    public void handshakeWriteFailureClosesSessionAndCancelsTimeout() throws Exception {
        // 让握手写入保持未完成，以便注入传输失败。
        Connection connection = new Connection();
        BasicFuture<Void> writing = new BasicFuture<>();
        connection.nextWrite = writing;
        ProtocolSession<byte[]> session = this.session(connection, new ArrayList<>(), Collections.emptyMap());
        session.connected();

        // 写入失败结束握手、取消超时并关闭传输。
        IOException failure = new IOException("handshake write failed");
        writing.failed(failure);
        assertSame(failure, session.ready().getCause());
        assertTrue(this.context.cancelled);
        assertFalse(connection.open);

        // 重入连接回调和迟到定时器不能重新写出握手。
        session.connected();
        this.context.task.run();
        assertEquals(1, connection.writes.size());
    }

    @Test
    public void cancellingResponseSendCancelsItsTransportWrite() throws Exception {
        // 完成握手并收到请求，准备延迟完成的响应写入。
        Connection connection = new Connection();
        ProtocolSession<byte[]> session = this.session(connection, new ArrayList<>(), Collections.emptyMap());
        session.connected();
        session.receive(fixture("handshake-v1.bin"));
        session.receive(fixture("request-v1.bin"));
        ResponsePayload response = (ResponsePayload) this.codec.decode(fixture("response-v1.bin"));
        BasicFuture<Void> writing = new BasicFuture<>();
        connection.nextWrite = writing;

        // 取消响应 future 必须向下传播，并让该请求的应答机会终结。
        Future<Void> sent = session.send(response);
        assertFalse(sent.isDone());
        assertTrue(sent.cancel());
        assertTrue(writing.isCancelled());
        assertNotNull(session.send(response).getCause());
    }

    @Test
    public void closeCompletesBothDirectionsOnceEvenWhenCallbacksReenter() throws Exception {
        // 同时准备入站请求和两个写入尚未完成的出站请求。
        Connection connection = new Connection();
        List<OptionInfo> received = new ArrayList<>();
        ProtocolSession<byte[]> session = this.session(connection, received, Collections.emptyMap());
        session.connected();
        session.receive(fixture("handshake-v1.bin"));
        session.receive(fixture("request-v1.bin"));
        RequestPayload incoming = (RequestPayload) received.remove(0);
        List<RequestPayload> requests = new ArrayList<>();
        List<Future<Void>> writes = new ArrayList<>();
        for (long id : new long[] { 101, 102 }) {
            RequestPayload request = (RequestPayload) this.codec.decode(fixture("request-v1.bin"));
            request.setRequestID(id);
            request.completion().onFinal(done -> session.closed(done.getCause()));
            requests.add(request);
            connection.nextWrite = new BasicFuture<>();
            writes.add(session.send(request));
        }

        // 关闭回调重入时，每个方向仍只完成一次。
        IOException failure = new IOException("peer closed");
        session.closed(failure);
        session.closed(failure);
        assertSame(failure, incoming.completion().getCause());
        for (int i = 0; i < requests.size(); i++) {
            assertSame(failure, requests.get(i).completion().getCause());
            assertSame(failure, writes.get(i).getCause());
        }
        assertEquals(2, received.size());
        for (OptionInfo message : received) {
            assertSame(failure, ((ThrowPayload) message).getThrowable());
        }

        // 关闭后忽略入站数据并拒绝新写入，不重复失败通知。
        int written = connection.writes.size();
        session.receive(fixture("request-v1.bin"));
        assertNotNull(session.send(requests.get(0)).getCause());
        assertEquals(written, connection.writes.size());
        assertEquals(2, received.size());
    }

    @Test
    public void outgoingCapacityIsReleasedByRpcCompletionWithoutWaitingForReply() throws Exception {
        // 仅允许一个待完成请求，先耗尽会话容量。
        Connection connection = new Connection();
        List<OptionInfo> messages = new ArrayList<>();
        Map<String, String> options = Collections.singletonMap("maxPendingRequests", "1");
        ProtocolSession<byte[]> session = this.session(connection, messages, options);
        session.connected();
        session.receive(fixture("handshake-v1.bin"));
        RequestPayload first = (RequestPayload) this.codec.decode(fixture("request-v1.bin"));
        first.setRequestID(101);
        session.send(first).get(2, TimeUnit.SECONDS);
        RequestPayload second = (RequestPayload) this.codec.decode(fixture("request-v1.bin"));
        second.setRequestID(102);
        int written = connection.writes.size();
        assertTrue(session.send(second).getCause() instanceof IOException);
        assertEquals(written, connection.writes.size());
        assertEquals(1, messages.size());
        assertTrue(messages.get(0) instanceof ThrowPayload);

        // RPC 完成后释放协议容量，下一请求不必等待迟到响应。
        first.complete(new TimeoutException("RPC expired"));
        session.send(second).get(2, TimeUnit.SECONDS);
        assertEquals(written + 1, connection.writes.size());
        second.complete(null);
        session.closed(null);
        assertEquals(1, messages.size());
    }

    @Test
    public void completedRequestDoesNotOccupyProtocolCapacity() throws Exception {
        // 容量仅为一的会话收到一个已完成的本地请求。
        Connection connection = new Connection();
        List<OptionInfo> messages = new ArrayList<>();
        Map<String, String> options = Collections.singletonMap("maxPendingRequests", "1");
        ProtocolSession<byte[]> session = this.session(connection, messages, options);
        session.connected();
        session.receive(fixture("handshake-v1.bin"));
        RequestPayload completed = (RequestPayload) this.codec.decode(fixture("request-v1.bin"));
        IOException expired = new IOException("RPC already finished");
        completed.complete(expired);
        int written = connection.writes.size();

        // 已完成请求不写出、不占用容量，也不产生重复失败通知。
        assertSame(expired, session.send(completed).getCause());
        assertEquals(written, connection.writes.size());
        assertTrue(messages.isEmpty());
        RequestPayload valid = (RequestPayload) this.codec.decode(fixture("request-v1.bin"));
        session.send(valid).get(2, TimeUnit.SECONDS);
        assertEquals(written + 1, connection.writes.size());
        valid.complete(null);
        session.closed(null);
    }

    @Test
    public void unencodableRequestDoesNotOccupyProtocolCapacity() throws Exception {
        // 容量仅为一的会话尝试发送无法序列化的请求。
        Connection connection = new Connection();
        List<OptionInfo> messages = new ArrayList<>();
        Map<String, String> options = Collections.singletonMap("maxPendingRequests", "1");
        ProtocolSession<byte[]> session = this.session(connection, messages, options);
        session.connected();
        session.receive(fixture("handshake-v1.bin"));
        RequestPayload broken = (RequestPayload) this.codec.decode(fixture("request-v1.bin"));
        broken.setSerializeType("missing");
        int written = connection.writes.size();

        // 编码失败产生一次通知，后续有效请求仍能写出。
        assertNotNull(session.send(broken).getCause());
        assertEquals(written, connection.writes.size());
        assertEquals(1, messages.size());
        RequestPayload valid = (RequestPayload) this.codec.decode(fixture("request-v1.bin"));
        session.send(valid).get(2, TimeUnit.SECONDS);
        assertEquals(written + 1, connection.writes.size());
        valid.complete(null);
        session.closed(null);
    }

    @Test
    public void outboundHandshakeDoesNotAdvertiseAnUnboundServiceAddress() throws Exception {
        // 出站端点没有监听地址，握手不得宣告虚构服务地址。
        Connection connection = new Connection();
        ProtocolSessionContext outbound = ProtocolTestAccess.context( //
                this.context.runtime, this.context::schedule, null, true);
        ProtocolConfig config = config(Collections.emptyMap());
        ProtocolSession<byte[]> session = TestRsfSession.createSession(config, outbound, connection, (payload, id) -> {
        });
        session.connected();

        // SERVER_INFO 为空，但对端握手仍能正常完成。
        ResponsePayload hello = (ResponsePayload) this.codec.decode(connection.writes.get(0));
        assertEquals("", hello.getOption("SERVER_INFO"));
        session.receive(connection.writes.get(0));
        assertTrue(session.ready().isDone());
    }

    private ProtocolSession<byte[]> session( //
            Connection connection, List<OptionInfo> messages, //
            Map<String, String> options) throws Exception {
        InterAddress address = new InterAddress("rsf", "127.0.0.1", 2181, "default");
        ProtocolSessionContext protocol = ProtocolTestAccess.context( //
                this.context.runtime, this.context::schedule, address, false);
        return TestRsfSession.createSession( //
                config(options), protocol, connection, (payload, id) -> messages.add(payload));
    }

    private ProtocolConfig config(Map<String, String> options) {
        Map<String, String> configured = new HashMap<>(options);
        return ProtocolTestAccess.config("rsf", "rsf", "rsf", configured);
    }

    private static final class Connection implements NetworkChannel<byte[]> {
        private       boolean           open   = true;
        private final List<byte[]>      writes = new ArrayList<>();
        private       BasicFuture<Void> nextWrite;

        public boolean isOpen() {
            return this.open;
        }

        public Future<Void> write(byte[] bytes) {
            this.writes.add(bytes);
            if (this.nextWrite != null) {
                BasicFuture<Void> result = this.nextWrite;
                this.nextWrite = null;
                return result;
            }
            return new BasicFuture<>((Void) null);
        }

        public Future<Void> close() {
            this.open = false;
            return new BasicFuture<>((Void) null);
        }

        public Future<Void> drainAndClose() {
            return this.close();
        }

        public void execute(Runnable task) {
            task.run();
        }

        public InterAddress getLocal() {
            return null;
        }

        public InterAddress getRemote() {
            return null;
        }
    }

    private static final class Context {
        private final MetContext runtime = serializationContext();
        private       Runnable   task;
        private       boolean    cancelled;

        private Cancellable schedule(Runnable task, long delay) {
            this.task = task;
            return () -> {
                boolean changed = !this.cancelled;
                this.cancelled = true;
                return changed;
            };
        }
    }
}
