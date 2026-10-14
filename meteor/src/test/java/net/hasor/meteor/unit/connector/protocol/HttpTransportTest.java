/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.protocol;
import java.io.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.protocol.EndpointConnectorFactory;
import net.hasor.meteor.support.connector.protocol.*;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** 直接验证连接器、队列和生命周期契约；真实网络的用户调用另见场景测试。 */
public class HttpTransportTest {
    private static final ReceivedListener RECEIVER = new Inbox();
    private volatile     boolean          timersStopped;

    private ConnectorConfig config(String schema, int port) throws Exception {
        return config(schema, port, TextProtocol.class);
    }

    private ConnectorConfig config(String schema, int port, Class<? extends TextProtocol> protocol) throws Exception {
        // 单线程传输便于观察队列，协议类型仍经生产 SPI 装配。
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        options.put("listenType", "http");
        options.put("protocol", protocol.getSimpleName().toLowerCase(Locale.ROOT));
        InterAddress localAddress = InterAddress.forBinding(schema, "127.0.0.1", port, "default");
        String protocolName = options.getOrDefault("protocol", schema);
        ProtocolConfig mount = new ProtocolConfig(schema, schema, protocolName, options);
        return new ConnectorConfig(schema, localAddress, options, Collections.singletonList(mount), true);
    }

    private Future<MetChannel> connect(Endpoint endpoint, InterAddress target) {
        return endpoint.connect(target);
    }

    private int port() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            return server.getLocalPort();
        }
    }

    private static RequestPayload request(long id, String value) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setSerializeType("Java");
        request.setClientTimeout(3000);
        request.addParameter("java.lang.String", value);
        return request;
    }

    private ResponsePayload response(ReceivedRequest exchange, String value) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(exchange.id);
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType("Java");
        response.setReturnType("java.lang.String");
        response.setReturnData(value);
        return response;
    }

    @Test
    public void unifiedPayloadRoundTripExposesConnectionIdentity() throws Exception {
        // 准备两端连接器，尚未发送时客户端不会建立物理连接。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox inbox = new Inbox();
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, requestInbox(requests)); //
             Endpoint client = new Endpoint(clientConfig, inbox)) {
            server.bind().get(2, TimeUnit.SECONDS);
            MetChannel outgoing = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
            assertNull(outgoing.getLocal());
            assertEquals(server.connector.getBindAddress(), outgoing.getRemote());

            // 首次发送建立连接，双方地址和所属连接器应互相对应。
            Payload request = request(1, "request");
            assertSame(outgoing, outgoing.sendData(request).get(2, TimeUnit.SECONDS));
            ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(incoming);
            assertSame(client.connector, outgoing.getConnector());
            assertSame(server.connector, incoming.channel.getConnector());
            assertEquals(outgoing.getLocal().getHostPort(), incoming.channel.getRemote().getHostPort());
            assertEquals(outgoing.getRemote().getHostPort(), incoming.channel.getLocal().getHostPort());
            for (MetChannel channel : Arrays.asList(outgoing, incoming.channel)) {
                assertEquals("http", channel.getLocal().getSchema());
                assertEquals("http", channel.getRemote().getSchema());
            }

            // 一次完整往返只能产生一个请求和对应响应。
            Payload response = response(incoming, "response");
            assertSame(incoming.channel, incoming.channel.sendData(response).get(2, TimeUnit.SECONDS));
            ResponsePayload received = inbox.expect(1).get(2, TimeUnit.SECONDS);
            assertEquals("response", received.getReturnData());
            assertTrue(requests.isEmpty());
        }
    }

    @Test
    public void localFailureOnOutgoingChannelDoesNotDisruptRoundTrip() throws Exception {
        // 准备可观察请求和响应的真实 HTTP 连接。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox inbox = new Inbox();
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, requestInbox(requests)); //
             Endpoint client = new Endpoint(clientConfig, inbox)) {
            server.bind().get(2, TimeUnit.SECONDS);
            MetChannel outgoing = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
            outgoing.sendData(request(1, "request")).get(2, TimeUnit.SECONDS);
            ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(incoming);

            // 本地失败通知不能写入网络，拒绝后连接仍须可用。
            Payload failure = new ThrowPayload(new IOException("local failure"));
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> {
                outgoing.sendData(failure).get(2, TimeUnit.SECONDS);
            });
            assertTrue(rejected.getCause() instanceof UnsupportedOperationException);
            assertTrue(outgoing.isActive());

            // 原请求仍可正常完成，证明失败没有污染 HTTP 交换。
            incoming.channel.sendData(response(incoming, "response")).get(2, TimeUnit.SECONDS);
            ResponsePayload received = inbox.expect(1).get(2, TimeUnit.SECONDS);
            assertEquals("response", received.getReturnData());
            assertTrue(requests.isEmpty());
        }
    }

    @Test
    public void localFailureOnAcceptedChannelDoesNotDisruptRoundTrip() throws Exception {
        // 准备可观察请求和响应的真实 HTTP 连接。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox inbox = new Inbox();
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, requestInbox(requests)); //
             Endpoint client = new Endpoint(clientConfig, inbox)) {
            server.bind().get(2, TimeUnit.SECONDS);
            MetChannel outgoing = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
            outgoing.sendData(request(1, "request")).get(2, TimeUnit.SECONDS);
            ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(incoming);

            // 本地失败通知不能写入网络，拒绝后连接仍须可用。
            Payload failure = new ThrowPayload(new IOException("local failure"));
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> {
                incoming.channel.sendData(failure).get(2, TimeUnit.SECONDS);
            });
            assertTrue(rejected.getCause() instanceof UnsupportedOperationException);
            assertTrue(incoming.channel.isActive());

            // 原请求仍可正常完成，证明失败没有污染 HTTP 交换。
            incoming.channel.sendData(response(incoming, "response")).get(2, TimeUnit.SECONDS);
            ResponsePayload received = inbox.expect(1).get(2, TimeUnit.SECONDS);
            assertEquals("response", received.getReturnData());
            assertTrue(requests.isEmpty());
        }
    }

    @Test
    public void outgoingChannelRejectsResponsePayload() throws Exception {
        // 准备可观察请求和响应的真实 HTTP 连接。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox inbox = new Inbox();
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, requestInbox(requests)); //
             Endpoint client = new Endpoint(clientConfig, inbox)) {
            server.bind().get(2, TimeUnit.SECONDS);
            MetChannel outgoing = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
            outgoing.sendData(request(1, "request")).get(2, TimeUnit.SECONDS);
            ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(incoming);

            // 客户端通道不能反向发送响应，拒绝不关闭原连接。
            ResponsePayload reverse = response(incoming, "wrong direction");
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> {
                outgoing.sendData(reverse).get(2, TimeUnit.SECONDS);
            });
            assertTrue(rejected.getCause() instanceof UnsupportedOperationException);
            assertTrue(outgoing.isActive());
        }
    }

    @Test
    public void closingChannelIsIdempotentAndClosesThePeer() throws Exception {
        // 准备可观察请求和响应的真实 HTTP 连接。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox inbox = new Inbox();
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, requestInbox(requests)); //
             Endpoint client = new Endpoint(clientConfig, inbox)) {
            server.bind().get(2, TimeUnit.SECONDS);
            MetChannel outgoing = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
            outgoing.sendData(request(1, "request")).get(2, TimeUnit.SECONDS);
            ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(incoming);

            // 先完成一次往返，再验证空闲连接关闭，保持原用例的关闭前提。
            incoming.channel.sendData(response(incoming, "response")).get(2, TimeUnit.SECONDS);
            ResponsePayload received = inbox.expect(1).get(2, TimeUnit.SECONDS);
            assertEquals("response", received.getReturnData());

            // 重复关闭与排空关闭返回同一完成结果。
            Future<MetChannel> closed = outgoing.close();
            assertSame(outgoing, closed.get(2, TimeUnit.SECONDS));
            assertFalse(outgoing.isActive());
            assertSame(closed, outgoing.close());
            assertSame(closed, outgoing.drainAndClose());

            // 有界观察对端状态，等待真正的网络关闭通知。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (incoming.channel.isActive() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertFalse(incoming.channel.isActive());
        }
    }

    @Test
    public void repeatedBindReusesTheConfiguredListener() throws Exception {
        // 同一连接器重复绑定，应复用已存在的监听器。
        ConnectorConfig endpointConfig = config("http", port());
        try (Endpoint endpoint = new Endpoint(endpointConfig, RECEIVER)) {
            MetListen first = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);

            MetListen repeated = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);
            assertEquals(endpoint.config.address(), first.getBindAddress());
            assertSame(first, repeated);
            assertEquals(1, endpoint.manager.find(endpoint.config.name()).getListenList().size());
        }
    }

    @Test
    public void closedListenerCanBeBoundAgain() throws Exception {
        // 先释放监听端口，再由同一连接器重新绑定。
        ConnectorConfig endpointConfig = config("http", port());
        try (Endpoint endpoint = new Endpoint(endpointConfig, RECEIVER)) {
            MetListen first = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);
            first.close();

            MetListen rebound = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);
            assertNotSame(first, rebound);
            assertTrue(rebound.isActive());
        }
    }

    @Test
    public void occupiedPortFailsThroughTheBindFuture() throws Exception {
        // 外部监听器占用端口，绑定错误必须通过 Future 报告。
        try (ServerSocket occupied = new ServerSocket(0)) {
            ConnectorConfig conflictConfig = config("http", occupied.getLocalPort());
            try (Endpoint conflict = new Endpoint(conflictConfig, RECEIVER)) {

                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    conflict.manager.bind(conflict.config.name()).get(2, TimeUnit.SECONDS);
                });
                assertNotNull(failure.getCause());
            }
        }
    }

    @Test
    public void exposesActualTypedListenerOnlyAfterBind() throws Exception {
        // 绑定前不创建连接器，绑定后公开实际 HTTP 监听器。
        ConnectorConfig connectorConfig = config("http", port());
        try (Endpoint connector = new Endpoint(connectorConfig, RECEIVER)) {
            assertNull(connector.connector);
            assertNull(connector.manager.find(connector.config.name()));

            // 绑定创建实际类型的监听器，重复绑定仍复用同一实例。
            connector.bind().get(2, TimeUnit.SECONDS);
            MetListen listen = connector.connector.getListenList().get(0);
            assertEquals("http", listen.getType());
            assertTrue(listen.isActive());
            assertEquals(connector.config.address(), listen.getBindAddress());
            assertEquals(listen.getBindAddress(), connector.connector.getBindAddress());
            connector.bind().get(2, TimeUnit.SECONDS);
            assertEquals(1, connector.connector.getListenList().size());
            assertSame(listen, connector.connector.getListenList().get(0));
        }
    }

    @Test
    public void closedListenerNoLongerExposesItsBindAddress() throws Exception {
        // 实际监听器关闭后，连接器不能继续暴露失效地址。
        ConnectorConfig configuration = config("http", 0);
        try (Endpoint endpoint = new Endpoint(configuration, RECEIVER)) {
            MetListen listener = endpoint.bind().get(2, TimeUnit.SECONDS);

            listener.close();
            assertFalse(listener.isActive());
            assertNull(endpoint.connector.getBindAddress());
        }
    }

    @Test
    public void connectorCloseRemovesPreviouslyClosedListeners() throws Exception {
        // 监听器先单独退出，随后关闭连接器仍须清理保留的监听列表。
        ConnectorConfig configuration = config("http", 0);
        try (Endpoint endpoint = new Endpoint(configuration, RECEIVER)) {
            MetListen listener = endpoint.bind().get(2, TimeUnit.SECONDS);
            listener.close();

            endpoint.connector.close();
            assertTrue(endpoint.connector.getListenList().isEmpty());
        }
    }

    @Test
    public void httpPipelinedRepliesStayOrderedAndKeepAliveAcceptsAnotherRequest() throws Exception {
        // 收集同一长连接上的请求，以相反完成顺序验证响应排序。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                exchange.payload = request;
                requests.add(exchange);
            }
        };
        ConnectorConfig serverConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
                socket.setSoTimeout(2000);
                OutputStream output = socket.getOutputStream();
                output.write(http("first"));
                output.write(http("second"));
                output.flush();
                ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
                ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(first);
                assertNotNull(second);

                // 后一个响应先提交也必须等待队首，完成后连接继续接收请求。
                Future<MetChannel> secondWrite = second.channel.sendData(response(second, "second"));
                assertFalse(secondWrite.isDone()); // 后一个响应等待前一个响应写出。
                first.channel.sendData(response(first, "first")).get(2, TimeUnit.SECONDS);
                assertSame(second.channel, secondWrite.get(2, TimeUnit.SECONDS));
                assertSame(first.channel, second.channel);
                assertEquals("first", readHttp(socket.getInputStream()));
                assertEquals("second", readHttp(socket.getInputStream()));

                // 排空本批流水线后，长连接继续接受下一次请求。
                output.write(http("third"));
                output.flush();
                ReceivedRequest third = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(third);
                third.channel.sendData(response(third, "third"));
                assertEquals("third", readHttp(socket.getInputStream()));
            }
        }
    }

    @Test
    public void httpClientQueuesCallsAndReusesTheConnection() throws Exception {
        // 连续请求共用一个客户端通道，记录服务端实际看到的对端地址。
        Set<String> peers = ConcurrentHashMap.newKeySet();
        Inbox received = new Inbox();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                peers.add(exchange.channel.getRemote().getHostPort());
                String value = (String) request.getParameterValues().get(0);
                exchange.channel.sendData(response(exchange, value));
            }
        };
        ConnectorConfig serverConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            // 客户端配置地址已由服务端占用，出站连接不能再绑定该端口。
            int occupiedPort = server.connector.getBindAddress().getPort();
            ConnectorConfig clientConfig = config("http", occupiedPort);
            try (Endpoint client = new Endpoint(clientConfig, received)) {
                MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                // 连续提交同类请求，响应必须保持关联并复用一个物理连接。
                for (int i = 1; i <= 10; i++) {
                    received.expect(i);
                    session.sendData(request(i, "http-" + i));
                }
                for (int i = 1; i <= 10; i++) {
                    ResponsePayload reply = received.expect(i).get(2, TimeUnit.SECONDS);
                    assertEquals("http-" + i, reply.getReturnData());
                    assertSame(session, received.sources.get((long) i));
                }
                assertEquals(1, peers.size());
            }
        }
    }

    @Test
    public void rpcCompletionReleasesWrittenHttpExchangeAndAllowsNextCall() throws Exception {
        // 首个请求已写出但不返回响应，观察 RPC 完成后的队列恢复。
        Inbox received = new Inbox();
        AtomicInteger requests = new AtomicInteger();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                if (requests.incrementAndGet() > 1) {
                    exchange.channel.sendData(response(exchange, "next"));
                }
            }
        };
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver); //
             Endpoint client = new Endpoint(clientConfig, received)) {
            server.bind().get(2, TimeUnit.SECONDS);
            client.bind().get(2, TimeUnit.SECONDS);
            MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
            RequestPayload first = request(1, "blocked");
            first.setClientTimeout(1);
            received.expect(1);
            Future<MetChannel> written = session.sendData(first);
            written.get(2, TimeUnit.SECONDS);

            // 超时由 RPC 层通知协议，随后队列中的请求应能继续发送。
            first.complete(new TimeoutException("RPC timed out"));
            assertSame(session, written.get(2, TimeUnit.SECONDS));
            try {
                received.expect(1).get(2, TimeUnit.SECONDS);
                fail();
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof TimeoutException);
            }
            received.expect(2);
            session.sendData(request(2, "next"));
            ResponsePayload nextResponse = received.expect(2).get(2, TimeUnit.SECONDS);
            assertEquals("next", nextResponse.getReturnData());
        }
    }

    @Test
    public void cancellingQueuedHttpRequestDoesNotSendItOrBreakTheNextReply() throws Exception {
        // 首个请求阻塞响应，让第二个请求保持在客户端发送队列。
        Inbox received = new Inbox();
        BlockingQueue<ReceivedRequest> arrivals = new LinkedBlockingQueue<>();
        List<String> values = new CopyOnWriteArrayList<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                values.add((String) request.getParameterValues().get(0));
                arrivals.add(exchange);
            }
        };
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver); //
             Endpoint client = new Endpoint(clientConfig, received)) {
            server.bind().get(2, TimeUnit.SECONDS);
            client.bind().get(2, TimeUnit.SECONDS);
            MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
            received.expect(1);
            session.sendData(request(1, "first"));
            ReceivedRequest first = arrivals.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);

            // 取消中间请求后继续提交第三个请求，验证取消只影响目标位置。
            Future<MetChannel> cancelled = session.sendData(request(2, "cancelled"));
            AtomicInteger notifications = new AtomicInteger();
            cancelled.onCancel(done -> notifications.incrementAndGet());
            assertTrue(cancelled.cancel());
            assertTrue(cancelled.isCancelled());
            assertEquals(1, notifications.get());
            received.expect(3);
            session.sendData(request(3, "third"));
            first.channel.sendData(response(first, "first"));
            ReceivedRequest third = arrivals.poll(2, TimeUnit.SECONDS);
            assertNotNull(third);
            third.channel.sendData(response(third, "third"));
            ResponsePayload firstResponse = received.expect(1).get(2, TimeUnit.SECONDS);
            assertEquals("first", firstResponse.getReturnData());
            ResponsePayload thirdResponse = received.expect(3).get(2, TimeUnit.SECONDS);
            assertEquals("third", thirdResponse.getReturnData());
            assertEquals(Arrays.asList("first", "third"), values);
        }
    }

    @Test
    public void completedWriteNotifiesCallbacksBeforeRpcResponse() throws Exception {
        // 服务端保留请求不响应，区分写入完成和 RPC 调用完成。
        Inbox received = new Inbox();
        BlockingQueue<ReceivedRequest> arrivals = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                arrivals.add(exchange);
            }
        };
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver); //
             Endpoint client = new Endpoint(clientConfig, received)) {
            server.bind().get(2, TimeUnit.SECONDS);
            client.bind().get(2, TimeUnit.SECONDS);
            MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
            Future<MetChannel> firstWrite = session.sendData(request(1, "blocked"));
            ReceivedRequest first = arrivals.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertSame(session, firstWrite.get(2, TimeUnit.SECONDS));

            // 写入已完成时注册回调，回调仍应立即收到同一通道。
            BasicFuture<MetChannel> notified = new BasicFuture<>();
            firstWrite.onCompleted(done -> notified.completed(done.getResult()));
            assertSame(session, notified.get(2, TimeUnit.SECONDS));
            assertFalse(received.expect(1).isDone());
        }
    }

    @Test
    public void closingChannelFailsQueuedRequestWithoutChangingCompletedWrite() throws Exception {
        // 首个请求写入后保留响应，构造尚未发送的排队请求。
        Inbox received = new Inbox();
        BlockingQueue<ReceivedRequest> arrivals = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                arrivals.add(exchange);
            }
        };
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver); //
             Endpoint client = new Endpoint(clientConfig, received)) {
            server.bind().get(2, TimeUnit.SECONDS);
            client.bind().get(2, TimeUnit.SECONDS);
            MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
            Future<MetChannel> firstWrite = session.sendData(request(1, "blocked"));
            ReceivedRequest first = arrivals.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertSame(session, firstWrite.get(2, TimeUnit.SECONDS));

            // 队首仍等待响应，关闭必须使尚未发送的请求失败。
            Future<MetChannel> pending = session.sendData(request(2, "closed before send"));
            session.close().get(2, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.get(2, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IOException);
            assertTrue(arrivals.isEmpty());
            assertSame(session, firstWrite.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void closedChannelReturnsFailedFutureForNewRequest() throws Exception {
        // 先建立真实连接，再观察关闭后的发送结果。
        Inbox received = new Inbox();
        BlockingQueue<ReceivedRequest> arrivals = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                arrivals.add(exchange);
            }
        };
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver); //
             Endpoint client = new Endpoint(clientConfig, received)) {
            server.bind().get(2, TimeUnit.SECONDS);
            client.bind().get(2, TimeUnit.SECONDS);
            MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
            Future<MetChannel> firstWrite = session.sendData(request(1, "blocked"));
            ReceivedRequest first = arrivals.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertSame(session, firstWrite.get(2, TimeUnit.SECONDS));

            // 已关闭通道拒绝新请求，并返回失败的发送 Future。
            session.close().get(2, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                session.sendData(request(2, "already closed")).get(2, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(arrivals.isEmpty());
        }
    }

    @Test
    public void rpcTimeoutFailsQueuedSendFuture() throws Exception {
        // 首个请求保持未响应，让待超时请求进入发送队列。
        Inbox received = new Inbox();
        BlockingQueue<ReceivedRequest> arrivals = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                arrivals.add(exchange);
            }
        };
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver); //
             Endpoint client = new Endpoint(clientConfig, received)) {
            server.bind().get(2, TimeUnit.SECONDS);
            client.bind().get(2, TimeUnit.SECONDS);
            MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
            session.sendData(request(1, "blocked")).get(2, TimeUnit.SECONDS);
            assertNotNull(arrivals.poll(2, TimeUnit.SECONDS));
            // 等待异步发送进入编码阶段，避免把“排队超时”测成“发送前已完成”。
            CountDownLatch encoding = new CountDownLatch(1);
            RequestPayload queued = new RequestPayload() {
                @Override
                public List<Object> getParameterValues() {
                    encoding.countDown();
                    return super.getParameterValues();
                }
            };
            queued.setRequestID(2);
            queued.setSerializeType("Java");
            queued.addParameter("java.lang.String", "expire before send");
            queued.setClientTimeout(50);
            try {
                Future<MetChannel> queuedWrite = session.sendData(queued);
                assertTrue("Queued request did not enter encoding", encoding.await(2, TimeUnit.SECONDS));
                queued.complete(new TimeoutException("RPC timed out"));
                queuedWrite.get(2, TimeUnit.SECONDS);
                fail("Queued send must expire");
            } catch (ExecutionException expected) {
                Throwable failure = expected.getCause();
                assertTrue(String.valueOf(failure), failure instanceof TimeoutException);
            }
        }
    }

    @Test
    public void connectionFailureFailsTheSendFuture() throws Exception {
        // 客户端连接对象延迟建立物理连接，发送时才报告对端不可达。
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint client = new Endpoint(clientConfig, new Inbox())) {
            InterAddress missingAddress = new InterAddress("http", "127.0.0.1", port(), "default");
            MetChannel missing = connect(client, missingAddress).get(2, TimeUnit.SECONDS);

            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                missing.sendData(request(1, "unreachable")).get(2, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IOException);
        }
    }

    @Test
    public void incompatibleProtocolFactoryIsRejectedBeforeListening() throws Exception {
        // 将 TCP 协议错误挂载到 HTTP 传输，验证绑定前的类型校验。
        Map<String, String> options = new HashMap<>();
        options.put("listenType", "http");
        options.put("protocol", "wirea");
        InterAddress localAddress = new InterAddress("http://127.0.0.1:" + port() + "/default");
        String protocolName = options.getOrDefault("protocol", "http");
        ProtocolConfig mount = new ProtocolConfig("http", "http", protocolName, options);
        List<ProtocolConfig> configMounts = Collections.singletonList(mount);
        ConnectorConfig config = new ConnectorConfig("http", localAddress, options, configMounts, true);
        try (ConnectorManager manager = subscribedManager(runtimeContext(config), RECEIVER)) {
            manager.bind("http").get(2, TimeUnit.SECONDS);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("requires transport tcp"));
        }
    }

    @Test
    public void rpcCompletionReleasesOrderedSlotAndRejectsLateReply() throws Exception {
        // 两个请求共用响应顺序，首个请求由 RPC 层显式通知超时。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                exchange.payload = request;
                requests.add(exchange);
            }
        };
        ConnectorConfig serverConfig = config("http", 0, ExpiringProtocol.class);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(http("expire"));
                socket.getOutputStream().write(http("next"));
                socket.getOutputStream().flush();
                ReceivedRequest expired = requests.poll(2, TimeUnit.SECONDS);
                ReceivedRequest next = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(expired);
                assertNotNull(next);

                // 后一个响应已经提交，超时通知须释放前面的有序位置。
                next.channel.sendData(response(next, "next"));
                expired.payload.complete(new TimeoutException("RPC timed out"));
                String timeoutResponse = readHttp(socket.getInputStream(), 500);
                assertTrue(timeoutResponse.contains("timed out"));
                assertEquals("next", readHttp(socket.getInputStream()));
                assertTrue(expired.channel.isActive());

                // 已超时请求的迟到响应必须被明确拒绝。
                BasicFuture<Throwable> rejected = new BasicFuture<>();
                Future<MetChannel> lateWrite = expired.channel.sendData(response(expired, "late"));
                lateWrite.onCompleted(done -> rejected.failed(new AssertionError("Late response written")));
                lateWrite.onFailed(done -> rejected.completed(done.getCause()));
                assertTrue(rejected.get(2, TimeUnit.SECONDS) instanceof IllegalStateException);
            }
        }
    }

    private byte[] http(String body) {
        return HttpWirePeer.request(body);
    }

    private String readHttp(InputStream input) throws IOException {
        return readHttp(input, 200);
    }

    private String readHttp(InputStream input, int status) throws IOException {
        HttpWirePeer.Message response = HttpWirePeer.readMessage(input);
        assertTrue(response.firstLine().startsWith("HTTP/1.1 " + status));
        return response.body();
    }

    @Test
    public void requestExchangeDoesNotScheduleConnectorTimers() throws Exception {
        // 关闭连接器计时入口后，普通请求仍须正常往返。
        Inbox received = new Inbox();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                channel.sendData(response(new ReceivedRequest(channel, id), "reply"));
            }
        };
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver); //
             Endpoint client = new Endpoint(clientConfig, received)) {
            server.bind().get(2, TimeUnit.SECONDS);
            client.bind().get(2, TimeUnit.SECONDS);
            MetChannel session = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);

            // 禁用计时器后连续执行同类请求，确认无需传输层请求定时器。
            this.timersStopped = true;
            for (long id = 1; id <= 2; id++) {
                RequestPayload request = request(id, "no request timer");
                assertSame(session, session.sendData(request).get(2, TimeUnit.SECONDS));
                ResponsePayload reply = received.expect(id).get(2, TimeUnit.SECONDS);
                assertEquals("reply", reply.getReturnData());
                request.completion().get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void assembledReceiverHandlesEveryListenerAndOutgoingConnection() throws Exception {
        // 两个监听端点共用接收者，客户端分别建立真实连接。
        Inbox incoming = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                String value = (String) request.getParameterValues().get(0);
                exchange.channel.sendData(response(exchange, value));
            }
        };
        // 同一接收者接入两个真实监听端点，分别验证连接和响应。
        ConnectorConfig firstServerConfig = config("http", 0);
        ConnectorConfig secondServerConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        Inbox received = new Inbox();
        try (Endpoint firstServer = new Endpoint(firstServerConfig, incoming); //
             Endpoint secondServer = new Endpoint(secondServerConfig, incoming); //
             Endpoint client = new Endpoint(clientConfig, received)) {
            MetListen firstListen = firstServer.bind().get(2, TimeUnit.SECONDS);
            MetListen secondListen = secondServer.bind().get(2, TimeUnit.SECONDS);
            assertNotEquals(firstListen.getBindAddress(), secondListen.getBindAddress());

            // 分别调用两个监听端点，接收者应把结果送回各自出站通道。
            MetChannel first = connect(client, firstListen.getBindAddress()).get(2, TimeUnit.SECONDS);
            MetChannel additional = connect(client, firstListen.getBindAddress()).get(2, TimeUnit.SECONDS);
            assertNotSame(first, additional);
            first.sendData(request(1, "first")).get(2, TimeUnit.SECONDS);
            ResponsePayload firstResponse = received.expect(1).get(2, TimeUnit.SECONDS);
            assertEquals("first", firstResponse.getReturnData());
            MetChannel second = connect(client, secondListen.getBindAddress()).get(2, TimeUnit.SECONDS);
            assertNotSame(first, second);
            second.sendData(request(2, "second")).get(2, TimeUnit.SECONDS);
            ResponsePayload secondResponse = received.expect(2).get(2, TimeUnit.SECONDS);
            assertEquals("second", secondResponse.getReturnData());
            assertTrue(incoming.results.isEmpty());
        }
    }

    @Test
    public void acceptedChannelsKeepIdenticalRequestIdsIndependent() throws Exception {
        // 不同连接使用相同请求编号，响应只能返回各自的对端。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                assertEquals(request.getRequestID(), id);
                requests.add(new ReceivedRequest(channel, id));
            }
        };
        ConnectorConfig serverConfig = config("http", 0, SameIdProtocol.class);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            try (Socket a = new Socket("127.0.0.1", server.connector.getBindAddress().getPort()); //
                 Socket b = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
                a.setSoTimeout(2000);
                b.setSoTimeout(2000);
                a.getOutputStream().write(http("a"));
                ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
                b.getOutputStream().write(http("b"));
                ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(first);
                assertNotNull(second);

                // 即使编号相同，两个响应仍写回各自的连接。
                assertNotSame(first.channel, second.channel);
                assertEquals(first.id, second.id);
                Future<MetChannel> secondWrite = second.channel.sendData(response(second, "b"));
                assertSame(second.channel, secondWrite.get(2, TimeUnit.SECONDS));
                Future<MetChannel> firstWrite = first.channel.sendData(response(first, "a"));
                assertSame(first.channel, firstWrite.get(2, TimeUnit.SECONDS));
                assertEquals("a", readHttp(a.getInputStream()));
                assertEquals("b", readHttp(b.getInputStream()));
            }
        }
    }

    @Test
    public void acceptedChannelRejectsUnknownRequestIdWithoutLosingPendingReply() throws Exception {
        // 在服务端取得一个等待响应的已接收通道。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        ConnectorConfig serverConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, requestInbox(requests))) {
            server.bind().get(2, TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(http("request"));
                ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(incoming);

                // 错误请求编号不能消耗真实请求的响应位置。
                ResponsePayload unknown = response(incoming, "unknown");
                unknown.setRequestID(incoming.id + 1);
                assertReplyRejected(incoming.channel, unknown);
                incoming.channel.sendData(response(incoming, "response")).get(2, TimeUnit.SECONDS);
                assertEquals("response", readHttp(socket.getInputStream()));
            }
        }
    }

    @Test
    public void acceptedChannelRejectsDuplicateReply() throws Exception {
        // 在服务端取得一个等待响应的已接收通道。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        ConnectorConfig serverConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, requestInbox(requests))) {
            server.bind().get(2, TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(http("request"));
                ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(incoming);

                // 正常响应完成后，同一请求编号不能再次写入网络。
                incoming.channel.sendData(response(incoming, "response")).get(2, TimeUnit.SECONDS);
                assertEquals("response", readHttp(socket.getInputStream()));
                assertReplyRejected(incoming.channel, response(incoming, "duplicate"));
            }
        }
    }

    @Test
    public void acceptedChannelRejectsOutgoingRequest() throws Exception {
        // 在服务端取得一个等待响应的已接收通道。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        ConnectorConfig serverConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, requestInbox(requests))) {
            server.bind().get(2, TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(http("request"));
                ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(incoming);

                // 已接收 HTTP 通道只允许响应，不能主动发送反向请求。
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    incoming.channel.sendData(request(9, "reverse request")).get(2, TimeUnit.SECONDS);
                });
                assertTrue(failure.getCause() instanceof UnsupportedOperationException);
            }
        }
    }

    @Test
    public void ordinaryAcknowledgementKeepsTheFinalResponseSlot() throws Exception {
        // 前一个普通请求占住响应位置，后续响应须按 HTTP 顺序写出。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        ConnectorConfig configuration = config("http", 0, AcknowledgementProtocol.class);
        try (Endpoint server = new Endpoint(configuration, requestInbox(requests))) {
            server.bind().get(2, TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(http("normal"));
                socket.getOutputStream().write(http("next"));
                ReceivedRequest normal = requests.poll(2, TimeUnit.SECONDS);
                ReceivedRequest following = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(normal);
                assertNotNull(following);

                // 普通调用的接受确认只通知发送完成，最终响应仍占用原位置。
                ResponsePayload acknowledgement = response(normal, "accept");
                acknowledgement.setStatus(ProtocolStatus.Accept);
                Future<MetChannel> accepted = normal.channel.sendData(acknowledgement);
                assertSame(normal.channel, accepted.get(2, TimeUnit.SECONDS));
                Future<MetChannel> queued = following.channel.sendData(response(following, "next"));
                assertFalse(queued.isDone());

                normal.channel.sendData(response(normal, "normal")).get(2, TimeUnit.SECONDS);
                assertEquals("normal", readHttp(socket.getInputStream()));
                assertEquals("next", readHttp(socket.getInputStream()));
                assertSame(following.channel, queued.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void messageAcknowledgementCompletesItsOrderedResponseSlot() throws Exception {
        // 前一个普通请求占住响应位置，后续响应须按 HTTP 顺序写出。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        ConnectorConfig configuration = config("http", 0, AcknowledgementProtocol.class);
        try (Endpoint server = new Endpoint(configuration, requestInbox(requests))) {
            server.bind().get(2, TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(http("normal"));
                socket.getOutputStream().write(http("message"));
                ReceivedRequest normal = requests.poll(2, TimeUnit.SECONDS);
                ReceivedRequest following = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(normal);
                assertNotNull(following);

                // 消息确认就是最终响应，但仍需等待前一个 HTTP 位置。
                ResponsePayload acknowledgement = response(following, "message-ack");
                acknowledgement.setStatus(ProtocolStatus.Accept);
                Future<MetChannel> acknowledged = following.channel.sendData(acknowledgement);
                assertReplyRejected(following.channel, response(following, "late"));
                assertFalse(acknowledged.isDone());

                normal.channel.sendData(response(normal, "normal")).get(2, TimeUnit.SECONDS);
                assertEquals("normal", readHttp(socket.getInputStream()));
                assertEquals("message-ack", readHttp(socket.getInputStream()));
                assertSame(following.channel, acknowledged.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void encodingFailureProducesAnOrderedErrorResponse() throws Exception {
        // 前一个普通请求占住响应位置，后续响应须按 HTTP 顺序写出。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        ConnectorConfig configuration = config("http", 0, AcknowledgementProtocol.class);
        try (Endpoint server = new Endpoint(configuration, requestInbox(requests))) {
            server.bind().get(2, TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(http("normal"));
                socket.getOutputStream().write(http("broken"));
                ReceivedRequest normal = requests.poll(2, TimeUnit.SECONDS);
                ReceivedRequest following = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(normal);
                assertNotNull(following);

                // 编码异常占据自己的响应位置，不得越过前一个请求。
                Future<MetChannel> failedEncoding = following.channel.sendData(response(following, "broken"));
                assertFalse(failedEncoding.isDone());
                normal.channel.sendData(response(normal, "normal")).get(2, TimeUnit.SECONDS);

                assertEquals("normal", readHttp(socket.getInputStream()));
                String errorResponse = readHttp(socket.getInputStream(), 500);
                assertTrue(errorResponse.contains("encode failed"));
                assertSame(following.channel, failedEncoding.get(2, TimeUnit.SECONDS));
                assertReplyRejected(following.channel, response(following, "late"));
            }
        }
    }

    @Test
    public void channelClosureFailsQueuedHttpResponse() throws Exception {
        // 首个响应位置未完成，第二个响应已经提交但仍在排队。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        };
        ConnectorConfig serverConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(http("blocked"));
                socket.getOutputStream().write(http("queued"));
                ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
                ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(first);
                assertNotNull(second);

                // 已提交响应进入排队后触发关闭，并检查写入结果。
                Future<MetChannel> writing = second.channel.sendData(response(second, "queued"));
                first.channel.close();
                try {
                    writing.get(2, TimeUnit.SECONDS);
                    fail("Closing the source channel must fail queued replies");
                } catch (ExecutionException expected) {
                    assertNotNull(expected.getCause());
                }
                assertReplyRejected(first.channel, response(first, "late"));
            }
        }
    }

    @Test(timeout = 10000)
    public void managerCloseReleasesUnansweredSlotsAndDrainsSubmittedHttpResponse() throws Exception {
        // 关闭管理器时同时存在未答复位置和已提交响应。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        };
        ConnectorConfig serverConfig = config("http", 0, DrainProtocol.class);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver)) {
            ConnectorManager manager = server.manager;
            server.bind().get(2, TimeUnit.SECONDS);
            ExecutorService lifecycle = Executors.newSingleThreadExecutor();
            try (Socket socket = new Socket("127.0.0.1", server.connector.getBindAddress().getPort())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(http("unsubmitted"));
                socket.getOutputStream().write(http("submitted"));
                ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
                ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(first);
                assertNotNull(second);

                // 已提交响应进入排队后触发关闭，并检查写入结果。
                Future<MetChannel> writing = second.channel.sendData(response(second, "drained"));

                // 在独立线程关闭管理器，让对端继续读取排空结果。
                FutureTask<Void> closing = new FutureTask<>(manager::close, null);
                lifecycle.execute(closing);
                String closingResponse = readHttp(socket.getInputStream(), 500);
                assertTrue(closingResponse.contains("closing"));
                assertEquals("drained", readHttp(socket.getInputStream()));
                assertSame(second.channel, writing.get(2, TimeUnit.SECONDS));
                closing.get(3, TimeUnit.SECONDS);
                ResponsePayload lateResponse = response(first, "late");
                assertReplyRejected(first.channel, lateResponse);
                assertEquals(-1, socket.getInputStream().read());
            } finally {
                lifecycle.shutdown();
                assertTrue(lifecycle.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    private void assertReplyRejected(MetChannel channel, ResponsePayload response) throws Exception {
        try {
            channel.sendData(response).get(2, TimeUnit.SECONDS);
            fail("Unknown or completed request must reject a reply");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
    }

    @Test(timeout = 10000)
    public void drainDoesNotWaitForResponseAfterRequestWasWritten() throws Exception {
        // 外部服务器接收请求但不答复，排空只等待写入完成。
        Inbox inbox = new Inbox();
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint client = new Endpoint(clientConfig, inbox)) {
            try (ServerSocket listener = new ServerSocket(0)) {
                listener.setSoTimeout(2000);
                InterAddress target = new InterAddress("http", "127.0.0.1", listener.getLocalPort(), "default");
                MetChannel channel = connect(client, target).get(2, TimeUnit.SECONDS);
                RequestPayload request = request(1, "no response");
                request.setClientTimeout(30000);
                Future<MetChannel> sent = channel.sendData(request);
                try (Socket peer = listener.accept()) {
                    assertSame(channel, sent.get(2, TimeUnit.SECONDS));

                    // 排空关闭应完成自身 Future，并终结未响应调用。
                    Future<MetChannel> closed = channel.drainAndClose();
                    assertSame(channel, closed.get(2, TimeUnit.SECONDS));
                    assertSame(closed, channel.close());
                    assertFalse(channel.isActive());
                    ExecutionException unanswered = assertThrows(ExecutionException.class, () -> {
                        inbox.expect(1).get(2, TimeUnit.SECONDS);
                    });
                    assertNotNull(unanswered.getCause());
                    try {
                        channel.sendData(request(2, "rejected")).get(2, TimeUnit.SECONDS);
                        fail("Closed channel must reject new writes");
                    } catch (ExecutionException expected) {
                        assertNotNull(expected.getCause());
                    }
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void drainWritesQueuedRequestWithoutWaitingForPreviousResponse() throws Exception {
        // 队首不响应时开始排空，已提交的后续请求仍须写出。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        };
        ConnectorConfig serverConfig = config("http", 0);
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver); //
             Endpoint client = new Endpoint(clientConfig, new Inbox())) {
            server.bind().get(2, TimeUnit.SECONDS);
            MetChannel channel = connect(client, server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
            channel.sendData(request(1, "unanswered")).get(2, TimeUnit.SECONDS);
            assertNotNull(requests.poll(2, TimeUnit.SECONDS));
            Future<MetChannel> queued = channel.sendData(request(2, "submitted"));

            // 排空关闭应完成自身 Future，并终结未响应调用。
            Future<MetChannel> closed = channel.drainAndClose();
            assertSame(channel, queued.get(2, TimeUnit.SECONDS));
            assertSame(channel, closed.get(2, TimeUnit.SECONDS));
            assertFalse(channel.isActive());
        }
    }

    @Test(timeout = 10000)
    public void unusedHttpChannelClosesWithoutOpeningASocket() throws Exception {
        // 只创建逻辑通道，观察关闭时是否意外建立物理连接。
        ConnectorConfig clientConfig = config("http", 0);
        try (Endpoint client = new Endpoint(clientConfig, new Inbox())) {
            try (ServerSocket listener = new ServerSocket(0)) {
                listener.setSoTimeout(200);
                InterAddress target = new InterAddress("http", "127.0.0.1", listener.getLocalPort(), "default");
                MetChannel channel = connect(client, target).get(2, TimeUnit.SECONDS);
                assertSame(channel, channel.drainAndClose().get(2, TimeUnit.SECONDS));
                try (Socket unexpected = listener.accept()) {
                    fail("An unused HTTP channel must not open a physical connection");
                } catch (SocketTimeoutException expected) {
                    assertFalse(channel.isActive());
                }
            }
        }
    }

    private Inbox requestInbox(BlockingQueue<ReceivedRequest> requests) {
        return new Inbox() {
            @Override
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest received = new ReceivedRequest(channel, id);
                received.payload = request;
                requests.add(received);
            }
        };
    }

    private static class Inbox implements ReceivedListener {
        final Map<Long, MetChannel>                   sources = new ConcurrentHashMap<>();
        final Map<Long, BasicFuture<ResponsePayload>> results = new ConcurrentHashMap<>();

        BasicFuture<ResponsePayload> expect(long id) {
            return this.results.computeIfAbsent(id, key -> new BasicFuture<>());
        }

        public void onRequest(MetChannel channel, long id, RequestPayload request) {
            throw new AssertionError("Unexpected request");
        }

        public void onResponse(MetChannel channel, long id, ResponsePayload response) {
            assertEquals(response.getRequestID(), id);
            this.sources.put(id, channel);
            this.expect(id).completed(response);
        }

        public void onFailure(MetChannel channel, long id, ThrowPayload failure) {
            this.sources.put(id, channel);
            this.expect(id).failed(failure.getThrowable());
        }
    }

    private static MetContext runtimeContext(ConnectorConfig... configs) {
        ClassLoader loader = HttpTransportTest.class.getClassLoader();
        // 仅提供连接器契约所需的配置和序列化能力，不伪装成应用场景。
        InvocationHandler settingsHandler = (proxy, method, args) -> {
            if (StringUtils.equals("getConnectorConfigs", method.getName())) {
                return Arrays.asList(configs);
            }
            if (StringUtils.equals("getDefaultTimeout", method.getName())) {
                return 3000;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        Class<?>[] settingsInterfaces = { MetSettings.class };
        MetSettings settings = (MetSettings) Proxy.newProxyInstance(loader, settingsInterfaces, settingsHandler);

        // 连接器只可访问明确列出的上下文能力，其余调用立即失败。
        InvocationHandler contextHandler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getClassLoader":
                    return loader;
                case "getSettings":
                    return settings;
                case "getSerializeCoder":
                    if (ObjectUtils.equals("Java", args[0])) {
                        return new JavaSerializeCoder();
                    }
                    return null;
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        };
        return (MetContext) Proxy.newProxyInstance(loader, new Class<?>[] { MetContext.class }, contextHandler);
    }

    private static ConnectorManager subscribedManager(MetContext context, ReceivedListener receiver) {
        ConnectorManager manager = new ConnectorManager(context, preparedFactory(context));
        subscribe(manager, receiver);
        return manager;
    }

    private static EndpointConnectorFactory preparedFactory(MetContext context) {
        // 使用生产服务发现和配置准备，不复制传输或路由实现。
        EndpointConnectorFactory factory = new EndpointConnectorFactory();
        factory.listenTypes(context.getClassLoader());
        for (ConnectorConfig config : context.getSettings().getConnectorConfigs()) {
            factory.prepare(config);
        }
        return factory;
    }

    private static void subscribe(ConnectorManager manager, ReceivedListener receiver) {
        // 将统一载荷入口转发给当前测试的观察者。
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
    }

    /** 直接使用生产 SPI 和连接器的内部契约夹具。 */
    private final class Endpoint implements AutoCloseable {
        private final ConnectorConfig  config;
        private final ConnectorManager manager;
        private       MetConnector     connector;

        private Endpoint(ConnectorConfig config, ReceivedListener listener) {
            // 每个测试显式持有并关闭连接器管理器。
            this.config = config;
            MetContext context = runtimeContext(config);
            this.manager = new ConnectorManager(context, preparedFactory(context)) {
                @Override
                public Cancellable schedule(Runnable task, long delayMillis) {
                    if (HttpTransportTest.this.timersStopped) {
                        throw new RejectedExecutionException("Test timer stopped");
                    }
                    return super.schedule(task, delayMillis);
                }
            };
            subscribe(this.manager, listener);
        }

        @Override
        public void close() {
            this.manager.close();
        }

        private Future<MetListen> bind() {
            Future<MetListen> result = this.manager.bind(this.config.name());
            this.connector = this.manager.find(this.config.name());
            return result;
        }

        private Future<MetChannel> connect(InterAddress address) {
            Future<MetChannel> result = this.manager.connect(address);
            this.connector = this.manager.find(this.config.name());
            return result;
        }
    }

    private static final class ReceivedRequest {
        private final MetChannel     channel;
        private final long           id;
        private       RequestPayload payload;

        private ReceivedRequest(MetChannel channel, long id) {
            this.channel = channel;
            this.id = id;
        }
    }
}
