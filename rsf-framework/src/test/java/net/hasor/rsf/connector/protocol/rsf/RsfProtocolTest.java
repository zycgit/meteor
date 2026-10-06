/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol.rsf;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.*;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.ConnectorManager;
import net.hasor.rsf.connector.ProtocolConfig;
import net.hasor.rsf.connector.ReceivedListener;
import net.hasor.rsf.connector.RsfChannel;
import net.hasor.rsf.connector.protocol.EndpointConnectorFactory;
import net.hasor.rsf.connector.protocol.ProtocolContext;
import net.hasor.rsf.connector.protocol.rsf.codec.*;
import net.hasor.rsf.connector.transport.NetworkChannel;
import net.hasor.rsf.domain.OptionInfo;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;
import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class RsfProtocolTest {
    private static final ReceivedListener RECEIVER = new ReceivedListener() {
        public void onRequest(RsfChannel channel, long id, RequestPayload request) {
            throw new AssertionError("Unexpected request");
        }

        public void onResponse(RsfChannel channel, long id, ResponsePayload response) {
            throw new AssertionError("Unexpected response");
        }

        public void onFailure(RsfChannel channel, long id, ThrowPayload failure) {
            throw new AssertionError(failure.getThrowable());
        }
    };

    private final Context           context = new Context();
    private final ConnectorManager  manager = subscribedManager(this.context, RECEIVER);
    private final CodecAdapterForV1 codec   = new CodecAdapterForV1(this.manager.context());

    @After
    public void close() {
        this.manager.close();
    }

    private ProtocolConfig config(Map<String, String> options) {
        Map<String, String> configured = new HashMap<>(options);
        configured.put("listenType", "tcp");
        return new ProtocolConfig("rsf", "rsf", configured);
    }

    @Test
    public void originalRequestFixtureDecodesAndReencodesByteForByte() throws Throwable {
        byte[] fixture = fixture("request-v1.bin");
        RequestPayload request = this.codec.readRequestPayload(new WireBuffer(fixture));
        assertEquals(42, request.getRequestID());
        assertEquals("Echo", request.getServiceName());
        assertEquals("中文🙂", request.getParameterValues().get(0));
        assertEquals(0x1234, request.getFlags());
        assertEquals("legacy", request.getOption("trace"));
        assertArrayEquals(fixture, encode(request));
    }

    @Test
    public void legacyResponseRemainsReadableWithoutDuplicatingReturnData() throws Throwable {
        byte[] fixture = fixture("response-v1.bin");
        ResponsePayload response = this.codec.readResponsePayload(new WireBuffer(fixture));
        assertEquals("中文🙂", response.getReturnData());
        assertEquals(42, response.getRequestID());
        RpcResponseProtocolV1 wire = new RpcResponseProtocolV1();
        ResponseBlock legacy = wire.decode(new WireBuffer(fixture));
        ResponseBlock compact = this.codec.buildResponseBlock(response);
        try {
            WireBuffer original = new WireBuffer();
            wire.encode(legacy, original);
            assertArrayEquals(fixture, original.toByteArray());
            WireBuffer encoded = new WireBuffer();
            wire.encode(compact, encoded);
            assertEquals(fixture.length - legacy.readPool(legacy.getReturnData()).length - 4, encoded.readableBytes());
            ResponsePayload decoded = this.codec.readResponsePayload(new WireBuffer(encoded.toByteArray()));
            assertEquals(response.getRequestID(), decoded.getRequestID());
            assertEquals(response.getStatus(), decoded.getStatus());
            assertEquals(response.getSerializeType(), decoded.getSerializeType());
            assertEquals(response.getReturnType(), decoded.getReturnType());
            assertEquals(response.getReturnData(), decoded.getReturnData());
            for (String key : response.getOptionKeys()) {
                assertEquals(response.getOption(key), decoded.getOption(key));
            }
        } finally {
            legacy.release();
            compact.release();
        }
    }

    @Test
    public void fragmentedHandshakeAndCoalescedMessagesHaveIndependentFrames() throws Exception {
        Connection connection = new Connection();
        List<OptionInfo> received = new ArrayList<>();
        RsfSession session = session(connection, received, Collections.emptyMap());
        session.connected();
        assertFalse(session.ready().isDone());
        byte[] hello = fixture("handshake-v1.bin");
        for (byte value : hello) {
            session.receive(new byte[] { value });
        }
        assertTrue(session.ready().isDone());
        assertTrue(this.context.cancelled);
        session.send((RequestPayload) this.codec.decode(fixture("request-v1.bin"))).get();
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.write(fixture("request-v1.bin"));
        joined.write(fixture("response-v1.bin"));
        session.receive(joined.toByteArray());
        assertEquals(2, received.size());
        assertTrue(received.get(0) instanceof RequestPayload);
        assertTrue(received.get(1) instanceof ResponsePayload);
    }

    @Test
    public void requestsCannotBeSentBeforeHandshakeAndHandshakeTimeoutClosesTransport() throws Exception {
        Connection connection = new Connection();
        RsfSession session = session(connection, new ArrayList<>(), Collections.emptyMap());
        session.connected();
        RequestPayload request = new RequestPayload();
        request.setRequestID(1);
        assertNotNull(session.send(request).getCause());
        assertEquals(1, connection.writes.size());
        this.context.task.run();
        assertFalse(connection.open);
        assertNotNull(session.ready().getCause());
    }

    @Test
    public void oversizedAndUnsupportedFramesAreRejectedBeforeDispatch() throws Exception {
        for (boolean oversized : new boolean[] { true, false }) {
            Map<String, String> options = new HashMap<>();
            options.put("maxFrameSize", "1024");
            RsfSession session = session(new Connection(), new ArrayList<>(), options);
            session.connected();
            byte[] invalid = fixture("handshake-v1.bin");
            if (oversized) {
                invalid = Arrays.copyOf(invalid, 13);
                invalid[10] = 0x7F;
            } else {
                invalid[0] = (byte) 0xB2;
            }
            try {
                session.receive(invalid);
                fail();
            } catch (IOException expected) {
            }
        }
    }

    @Test
    public void applicationFrameBeforeHandshakeIsRejected() throws Exception {
        Connection connection = new Connection();
        List<OptionInfo> received = new ArrayList<>();
        RsfSession session = this.session(connection, received, Collections.emptyMap());
        session.connected();
        try {
            session.receive(this.fixture("request-v1.bin"));
            fail("Application data cannot complete the handshake");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Expected RSF handshake"));
        }
        assertFalse(session.ready().isDone());
        assertTrue(received.isEmpty());
    }

    @Test
    public void handshakeWriteFailureClosesSessionAndCancelsTimeout() throws Exception {
        Connection connection = new Connection();
        BasicFuture<Void> writing = new BasicFuture<>();
        connection.nextWrite = writing;
        RsfSession session = this.session(connection, new ArrayList<>(), Collections.emptyMap());
        session.connected();
        IOException failure = new IOException("handshake write failed");
        writing.failed(failure);
        assertSame(failure, session.ready().getCause());
        assertTrue(this.context.cancelled);
        assertFalse(connection.open);
        session.connected();
        this.context.task.run();
        assertEquals(1, connection.writes.size());
    }

    @Test
    public void cancellingResponseSendCancelsItsTransportWrite() throws Exception {
        Connection connection = new Connection();
        RsfSession session = this.session(connection, new ArrayList<>(), Collections.emptyMap());
        session.connected();
        session.receive(this.fixture("handshake-v1.bin"));
        session.receive(this.fixture("request-v1.bin"));
        ResponsePayload response = (ResponsePayload) this.codec.decode(this.fixture("response-v1.bin"));
        BasicFuture<Void> writing = new BasicFuture<>();
        connection.nextWrite = writing;
        Future<Void> sent = session.send(response);
        assertFalse(sent.isDone());
        assertTrue(sent.cancel());
        assertTrue(writing.isCancelled());
        assertNotNull(session.send(response).getCause());
    }

    @Test
    public void closeCompletesBothDirectionsOnceEvenWhenCallbacksReenter() throws Exception {
        Connection connection = new Connection();
        List<OptionInfo> received = new ArrayList<>();
        RsfSession session = this.session(connection, received, Collections.emptyMap());
        session.connected();
        session.receive(this.fixture("handshake-v1.bin"));
        session.receive(this.fixture("request-v1.bin"));
        RequestPayload incoming = (RequestPayload) received.remove(0);
        List<RequestPayload> requests = new ArrayList<>();
        List<Future<Void>> writes = new ArrayList<>();
        for (long id : new long[] { 101, 102 }) {
            RequestPayload request = (RequestPayload) this.codec.decode(this.fixture("request-v1.bin"));
            request.setRequestID(id);
            request.completion().onFinal(done -> session.closed(done.getCause()));
            requests.add(request);
            connection.nextWrite = new BasicFuture<>();
            writes.add(session.send(request));
        }
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
        int written = connection.writes.size();
        session.receive(this.fixture("request-v1.bin"));
        assertNotNull(session.send(requests.get(0)).getCause());
        assertEquals(written, connection.writes.size());
        assertEquals(2, received.size());
    }

    @Test
    public void unsignedParameterAndOptionCountsRoundTrip() throws Throwable {
        RequestPayload request = this.codec.readRequestPayload(new WireBuffer(fixture("request-v1.bin")));
        for (int i = 1; i < 200; i++) {
            request.addParameter("java.lang.String", "v" + i);
            request.addOption("key" + i, "value" + i);
        }
        RequestPayload decoded = this.codec.readRequestPayload(new WireBuffer(encode(request)));
        assertEquals(200, decoded.getParameterValues().size());
        assertEquals("v199", decoded.getParameterValues().get(199));
        assertEquals("value199", decoded.getOption("key199"));
        for (int i = 200; i <= 255; i++) {
            request.addParameter("java.lang.String", "overflow");
        }
        try {
            encode(request);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void heapPoolOwnsDataAndRejectsTruncatedOrNegativeLengths() {
        PoolBlock pool = new PoolBlock();
        byte[] value = { 7 };
        pool.pushData(value);
        value[0] = 9;
        assertArrayEquals(new byte[] { 7 }, pool.readPool((short) 0));
        pool.pushData(null);
        pool.pushData(new byte[0]);
        WireBuffer wire = new WireBuffer();
        pool.fillTo(wire);
        PoolBlock decoded = new PoolBlock();
        decoded.fillFrom(new WireBuffer(wire.toByteArray()));
        pool.release();
        pool.release();
        assertArrayEquals(new byte[] { 7 }, decoded.readPool((short) 0));
        assertNull(decoded.readPool((short) 1));
        assertEquals(0, decoded.readPool((short) 2).length);
        for (int length : new int[] { -2, 100 }) {
            WireBuffer malformed = new WireBuffer();
            malformed.writeShort(1).writeInt(length);
            try {
                new PoolBlock().fillFrom(malformed);
                fail();
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    private byte[] encode(RequestPayload request) throws Exception {
        RequestBlock block = this.codec.buildRequestBlock(request);
        WireBuffer out = new WireBuffer();
        try {
            this.codec.writeRequestBlock(block, out);
            return out.toByteArray();
        } finally {
            block.release();
        }
    }

    private RsfSession session(Connection connection, List<OptionInfo> messages, Map<String, String> options) throws Exception {
        return new RsfSession(config(options), new ProtocolContext(this.manager.context(), this.manager::schedule, new InterAddress("rsf", "127.0.0.1", 2181, "default"), false), connection, (id, payload) -> messages.add(payload));
    }

    private byte[] fixture(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/legacy/" + name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static final class Connection implements NetworkChannel<byte[]> {
        boolean      open   = true;
        List<byte[]> writes = new ArrayList<>();
        private BasicFuture<Void> nextWrite;

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

    private static RsfContext runtimeContext() {
        ClassLoader loader = RsfProtocolTest.class.getClassLoader();
        RsfSettings settings = (RsfSettings) Proxy.newProxyInstance(loader, new Class<?>[] { RsfSettings.class }, (proxy, method, args) -> {
            if ("getConnectorConfigs".equals(method.getName())) {
                return Collections.emptySet();
            }
            if ("getDefaultTimeout".equals(method.getName())) {
                return 6000;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        return (RsfContext) Proxy.newProxyInstance(loader, new Class<?>[] { RsfContext.class }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getClassLoader":
                    return loader;
                case "getSettings":
                    return settings;
                case "getSerializeCoder":
                    return "Java".equals(args[0]) ? new JavaSerializeCoder() : null;
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        });
    }

    private static final class Context {
        private final RsfContext runtime = runtimeContext();
        Runnable task;
        boolean  cancelled;
    }

    private static ConnectorManager subscribedManager(Context context, ReceivedListener receiver) {
        ConnectorManager manager = new ConnectorManager(context.runtime, new EndpointConnectorFactory()) {
            @Override
            public Cancellable schedule(Runnable task, long delay) {
                context.task = task;
                return () -> {
                    boolean changed = !context.cancelled;
                    context.cancelled = true;
                    return changed;
                };
            }
        };
        manager.init();
        manager.subscribe((channel, id, payload) -> {
            switch (payload.getType()) {
                case REQUEST:
                    receiver.onRequest(channel, id, (RequestPayload) payload);
                    break;
                case RESPONSE:
                    receiver.onResponse(channel, id, (ResponsePayload) payload);
                    break;
                case THROW:
                    receiver.onFailure(channel, id, (ThrowPayload) payload);
                    break;
            }
        });
        return manager;
    }
}
