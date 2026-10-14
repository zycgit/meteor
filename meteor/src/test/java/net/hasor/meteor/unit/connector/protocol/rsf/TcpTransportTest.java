/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.protocol.rsf;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
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
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** 连接器与协议 SPI 契约的单元覆盖，使用真实 socket 验证传输及资源行为。 */
public class TcpTransportTest {
    private static final ReceivedListener RECEIVER = new Inbox();
    private volatile     boolean          timersStopped;

    private ConnectorConfig config(String schema, int port) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        options.put("listenType", "tcp");
        options.put("protocol", "rsf");
        InterAddress address = new InterAddress(schema + "://127.0.0.1:" + port + "/default");
        ProtocolConfig protocol = new ProtocolConfig(schema, schema, options.getOrDefault("protocol", schema), options);
        return new ConnectorConfig(schema, address, options, Collections.singletonList(protocol), true);
    }

    private int port() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            return server.getLocalPort();
        }
    }

    private RequestPayload request(long id, String value) {
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

    @Test(timeout = 10000)
    public void closingListenerKeepsEstablishedAndOutgoingChannelsUsable() throws Exception {
        // 准备已建立的请求通道，观察停止监听对现有连接的隔离。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        };
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            Inbox inbox = new Inbox();
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, inbox)) {
                MetChannel outgoing = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                outgoing.sendData(request(1, "first")).get(2, TimeUnit.SECONDS);
                ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(first);

                // 关闭监听器应拒绝新连接，但不影响已有通道及新的出站连接。
                InterAddress oldAddress = server.connector.getBindAddress();
                server.connector.closeBind();
                server.connector.closeBind();
                assertTrue(server.connector.getListenList().isEmpty());
                assertNull(server.connector.getBindAddress());
                try (Socket rejected = new Socket()) {
                    try {
                        rejected.connect(oldAddress.toSocketAddress(), 1000);
                        fail("Closed listener must refuse new connections");
                    } catch (ConnectException expected) {
                        // 已建立通道仍须可用。
                    }
                }
                ConnectorConfig peerSettings = config("rsf", port());
                try (Endpoint peer = new Endpoint(peerSettings, RECEIVER)) {
                    peer.bind().get(2, TimeUnit.SECONDS);
                    InterAddress peerAddress = peer.connector.getBindAddress();
                    MetChannel newOutgoing = server.connector.connect(peerAddress).get(2, TimeUnit.SECONDS);
                    assertTrue(newOutgoing.isActive());
                    newOutgoing.close().get(2, TimeUnit.SECONDS);
                    first.channel.sendData(response(first, "still-writable")).get(2, TimeUnit.SECONDS);
                    ResponsePayload remainingReply = inbox.expect(1).get(2, TimeUnit.SECONDS);
                    assertEquals("still-writable", remainingReply.getReturnData());
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void managerCloseDoesNotWaitForPendingRpcReply() throws Exception {
        // 建立一条等待服务端回复的真实 TCP 请求。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        };
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverReceiver)) {
            ConnectorManager manager = server.manager;
            server.bind().get(2, TimeUnit.SECONDS);
            Inbox inbox = new Inbox();
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, inbox)) {
                MetChannel outgoing = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                outgoing.sendData(request(1, "first")).get(2, TimeUnit.SECONDS);
                ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(first);

                // 仍有请求等待业务响应时，关闭管理器不能等待 RPC 回复。
                ExecutorService lifecycle = Executors.newSingleThreadExecutor();
                try {
                    lifecycle.submit(manager::close).get(2, TimeUnit.SECONDS);
                    assertFalse(first.channel.isActive());
                    assertNotNull(server.connector.connect(server.config.address()).getCause());
                    Future<MetChannel> lateReply = first.channel.sendData(response(first, "late"));
                    assertTrue(lateReply.getCause() instanceof IllegalStateException);
                } finally {
                    lifecycle.shutdownNow();
                }
            }
        }
    }

    @Test
    public void outgoingChannelRejectsLocalFailureWithoutDisruptingRoundTrip() throws Exception {
        // 建立请求通道并核对两端地址与连接器归属。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox inbox = new Inbox();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        };
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, inbox)) {
                MetChannel outgoing = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                assertNotNull(outgoing.getLocal());
                assertEquals(server.connector.getBindAddress(), outgoing.getRemote());
                Payload request = request(1, "request");
                assertSame(outgoing, outgoing.sendData(request).get(2, TimeUnit.SECONDS));
                ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(incoming);
                assertSame(client.connector, outgoing.getConnector());
                assertSame(server.connector, incoming.channel.getConnector());
                assertEquals(outgoing.getLocal().getHostPort(), incoming.channel.getRemote().getHostPort());
                assertEquals(outgoing.getRemote().getHostPort(), incoming.channel.getLocal().getHostPort());
                for (MetChannel channel : Arrays.asList(outgoing, incoming.channel)) {
                    assertEquals("rsf", channel.getLocal().getSchema());
                    assertEquals("rsf", channel.getRemote().getSchema());
                }

                // 出站通道拒绝本地失败通知后，仍保持原业务请求有效。
                Payload failure = new ThrowPayload(new IOException("local failure"));
                try {
                    outgoing.sendData(failure).get(2, TimeUnit.SECONDS);
                    fail("Failure notifications cannot be sent over the network");
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof UnsupportedOperationException);
                }
                assertTrue(outgoing.isActive());

                // 失败后仍能回复原请求，证明业务往返未被污染。
                Payload response = response(incoming, "response");
                assertSame(incoming.channel, incoming.channel.sendData(response).get(2, TimeUnit.SECONDS));
                ResponsePayload returned = inbox.expect(1).get(2, TimeUnit.SECONDS);
                assertEquals("response", returned.getReturnData());
                assertTrue(requests.isEmpty());
            }
        }
    }

    @Test
    public void acceptedChannelRejectsLocalFailureWithoutDisruptingRoundTrip() throws Exception {
        // 建立连接并发送业务请求，取得服务端实际接受的通道。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox inbox = new Inbox();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        };
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, inbox)) {
                MetChannel outgoing = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                assertNotNull(outgoing.getLocal());
                assertEquals(server.connector.getBindAddress(), outgoing.getRemote());
                Payload request = request(1, "request");
                assertSame(outgoing, outgoing.sendData(request).get(2, TimeUnit.SECONDS));
                ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(incoming);

                // 服务端接受的通道拒绝本地失败通知后，仍保持原业务请求有效。
                Payload failure = new ThrowPayload(new IOException("local failure"));
                try {
                    incoming.channel.sendData(failure).get(2, TimeUnit.SECONDS);
                    fail("Failure notifications cannot be sent over the network");
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof UnsupportedOperationException);
                }
                assertTrue(incoming.channel.isActive());

                // 失败后仍能回复原请求，证明业务往返未被污染。
                Payload response = response(incoming, "response");
                assertSame(incoming.channel, incoming.channel.sendData(response).get(2, TimeUnit.SECONDS));
                ResponsePayload returned = inbox.expect(1).get(2, TimeUnit.SECONDS);
                assertEquals("response", returned.getReturnData());
                assertTrue(requests.isEmpty());
            }
        }
    }

    @Test
    public void closingChannelIsIdempotentAndClosesPeerChannel() throws Exception {
        // 取得同一 TCP 连接的两端通道，观察关闭传播。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox inbox = new Inbox();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest received = new ReceivedRequest(channel, id);
                received.payload = request;
                requests.add(received);
            }
        };
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, inbox)) {
                MetChannel outgoing = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                assertNotNull(outgoing.getLocal());
                assertEquals(server.connector.getBindAddress(), outgoing.getRemote());
                Payload request = request(1, "request");
                assertSame(outgoing, outgoing.sendData(request).get(2, TimeUnit.SECONDS));
                ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(incoming);
                // 关闭结果幂等，并在有界等待内传递到对端通道。
                Future<MetChannel> closed = outgoing.close();
                assertSame(outgoing, closed.get(2, TimeUnit.SECONDS));
                assertFalse(outgoing.isActive());
                assertSame(closed, outgoing.close());
                assertSame(closed, outgoing.drainAndClose());
                ExecutionException peerClosed = assertThrows(ExecutionException.class, () -> {
                    incoming.payload.completion().get(2, TimeUnit.SECONDS);
                });
                assertNotNull(peerClosed.getCause());
                assertFalse(incoming.channel.isActive());
            }
        }
    }

    @Test
    public void repeatedBindReturnsTheConfiguredListener() throws Exception {
        // 绑定显式配置的地址，重复绑定应复用同一监听器。
        ConnectorConfig endpointSettings = config("rsf", port());
        try (Endpoint endpoint = new Endpoint(endpointSettings, RECEIVER)) {
            MetListen first = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);
            assertEquals(endpoint.config.address(), first.getBindAddress());
            MetListen repeated = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);
            assertSame(first, repeated);
            MetConnector boundConnector = endpoint.manager.find(endpoint.config.name());
            assertEquals(1, boundConnector.getListenList().size());
        }
    }

    @Test
    public void closedListenerCanBeBoundAgain() throws Exception {
        // 绑定后释放监听器，再次绑定同一个端点。
        ConnectorConfig endpointSettings = config("rsf", port());
        try (Endpoint endpoint = new Endpoint(endpointSettings, RECEIVER)) {
            MetListen first = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);
            assertEquals(endpoint.config.address(), first.getBindAddress());
            MetListen repeated = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);
            assertSame(first, repeated);
            MetConnector boundConnector = endpoint.manager.find(endpoint.config.name());
            assertEquals(1, boundConnector.getListenList().size());

            // 释放监听后重新绑定，必须得到新的有效监听器。
            first.close();
            MetListen rebound = endpoint.manager.bind(endpoint.config.name()).get(2, TimeUnit.SECONDS);
            assertNotSame(first, rebound);
            assertTrue(rebound.isActive());
        }
    }

    @Test
    public void occupiedPortFailsTheBindFuture() throws Exception {
        // 用原始 socket 占据端口，绑定失败应通过 future 返回。
        try (ServerSocket occupied = new ServerSocket(0)) {
            ConnectorConfig conflictSettings = config("rsf", occupied.getLocalPort());
            try (Endpoint conflict = new Endpoint(conflictSettings, RECEIVER)) {
                try {
                    conflict.manager.bind(conflict.config.name()).get(2, TimeUnit.SECONDS);
                    fail("Occupied port must fail through the future");
                } catch (ExecutionException expected) {
                    assertNotNull(expected.getCause());
                }
            }
        }
    }

    @Test
    public void exposesActualTypedListenerOnlyAfterBind() throws Exception {
        // 连接器尚未绑定时，不应暴露虚构监听器。
        ConnectorConfig connectorSettings = config("rsf", port());
        try (Endpoint connector = new Endpoint(connectorSettings, RECEIVER)) {
            assertNull(connector.connector);
            assertNull(connector.manager.find(connector.config.name()));

            // 真实绑定后才可查询类型、地址和活动状态。
            connector.bind().get(2, TimeUnit.SECONDS);
            MetListen listen = connector.connector.getListenList().get(0);
            assertEquals("tcp", listen.getType());
            assertTrue(listen.isActive());
            assertEquals(connector.config.address(), listen.getBindAddress());
            assertEquals(listen.getBindAddress(), connector.connector.getBindAddress());
            connector.bind().get(2, TimeUnit.SECONDS);
            assertEquals(1, connector.connector.getListenList().size());
            assertSame(listen, connector.connector.getListenList().get(0));

            // 监听器退出后清理可见地址，连接器关闭后清空监听列表。
            listen.close();
            assertFalse(listen.isActive());
            assertNull(connector.connector.getBindAddress());
            connector.connector.close();
            assertTrue(connector.connector.getListenList().isEmpty());
        }
    }

    @Test
    public void tcpConcurrentRepliesKeepRequestIdentity() throws Exception {
        // 并行积累多个待回复请求，随后由服务端按逆序回复。
        Inbox received = new Inbox();
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                requests.add(exchange);
            }
        };
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            // 出站端配置的本地地址已被占用，建立连接不能隐式执行监听绑定。
            ConnectorConfig clientSettings = config("rsf", server.connector.getBindAddress().getPort());
            try (Endpoint client = new Endpoint(clientSettings, received)) {
                MetChannel session = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                for (int i = 1; i <= 20; i++) {
                    received.expect(i);
                    session.sendData(request(i, "value-" + i));
                }
                List<ReceivedRequest> exchanges = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    ReceivedRequest exchange = requests.poll(2, TimeUnit.SECONDS);
                    assertNotNull(exchange);
                    exchanges.add(exchange);
                }

                // 逆序回复，验证客户端按请求 ID 匹配而非按收到的顺序匹配。
                Collections.reverse(exchanges);
                for (ReceivedRequest exchange : exchanges) {
                    exchange.channel.sendData(response(exchange, "value-" + exchange.id)).get(2, TimeUnit.SECONDS);
                }
                for (int i = 1; i <= 20; i++) {
                    ResponsePayload matchingReply = received.expect(i).get(2, TimeUnit.SECONDS);
                    assertEquals("value-" + i, matchingReply.getReturnData());
                }
            }
        }
    }

    @Test
    public void peerClosureFailsPendingRequestAndNewConnectionWorks() throws Exception {
        // 建立一个已发送但未收到回复的请求，准备模拟对端退出。
        Inbox received = new Inbox();
        CountDownLatch arrived = new CountDownLatch(1);
        ConnectorConfig endpoint = config("rsf", port());
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                arrived.countDown();
            }
        };
        try (Endpoint server = new Endpoint(endpoint, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, received)) {
                client.bind().get(2, TimeUnit.SECONDS);
                MetChannel first = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                received.expect(1);
                Future<MetChannel> written = first.sendData(request(1, "pending"));
                assertSame(first, written.get(2, TimeUnit.SECONDS));
                BasicFuture<MetChannel> notified = new BasicFuture<>();
                written.onCompleted(done -> notified.completed(done.getResult()));
                assertSame(first, notified.get(2, TimeUnit.SECONDS));
                assertTrue(arrived.await(2, TimeUnit.SECONDS));
                assertFalse(received.expect(1).isDone());

                // 对端退出后，等待响应的 future 必须结束。
                server.connector.close();
                try {
                    received.expect(1).get(2, TimeUnit.SECONDS);
                    fail();
                } catch (ExecutionException expected) {
                    assertNotNull(expected.getCause());
                }
                assertSame(first, written.get(2, TimeUnit.SECONDS)); // 响应失败不能改变已成功的发送。
                try {
                    first.sendData(request(3, "closed")).get(2, TimeUnit.SECONDS);
                    fail("Closed session send must fail through its future");
                } catch (ExecutionException expected) {
                    Throwable cause = expected.getCause();
                    boolean disconnected = cause instanceof IOException;
                    boolean closed = cause instanceof IllegalStateException;
                    assertTrue(disconnected || closed);
                }

                // 在相同服务地址恢复监听，新连接可正常往返。
                Inbox replacementReceiver = new Inbox() {
                    public void onRequest(MetChannel channel, long id, RequestPayload request) {
                        ReceivedRequest exchange = new ReceivedRequest(channel, id);
                        exchange.channel.sendData(response(exchange, "reconnected"));
                    }
                };
                try (Endpoint replacement = new Endpoint(endpoint, replacementReceiver)) {
                    replacement.bind().get(2, TimeUnit.SECONDS);
                    MetChannel second = client.connect(replacement.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                    assertNotSame(first, second);
                    received.expect(2);
                    second.sendData(request(2, "next"));
                    ResponsePayload reconnectedReply = received.expect(2).get(2, TimeUnit.SECONDS);
                    assertEquals("reconnected", reconnectedReply.getReturnData());
                }
            }
        }
    }

    @Test
    public void closedConnectorRejectsSendsBeforeEventLoopSubmission() throws Exception {
        // 连接真实服务端，然后关闭本端连接器。
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, RECEIVER)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, new Inbox())) {
                MetChannel session = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);

                // 本端连接器关闭后，发送必须在提交事件循环前失败。
                client.connector.close();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    session.sendData(request(4, "event loop closed")).get(2, TimeUnit.SECONDS);
                });
                assertTrue(failure.getCause() instanceof IllegalStateException);
            }
        }
    }

    @Test
    public void tcpConnectDoesNotCompleteUntilProtocolHandshake() throws Exception {
        // 原始 TCP 对端接受连接但不完成 RSF 握手。
        try (ServerSocket silentPeer = new ServerSocket(0)) {
            silentPeer.setSoTimeout(2000);
            Map<String, String> options = new HashMap<>();
            options.put("handshakeTimeout", "200");
            options.put("listenType", "tcp");
            options.put("protocol", "rsf");
            InterAddress localAddress = config("rsf", port()).address();
            String protocolName = options.getOrDefault("protocol", "rsf");
            ProtocolConfig mount = new ProtocolConfig("rsf", "rsf", protocolName, options);
            List<ProtocolConfig> mounts = Collections.singletonList(mount);
            ConnectorConfig shortHandshake = new ConnectorConfig("rsf", localAddress, options, mounts, true);
            try (Endpoint client = new Endpoint(shortHandshake, new Inbox())) {
                client.bind().get(2, TimeUnit.SECONDS);
                InterAddress target = new InterAddress("rsf", "127.0.0.1", silentPeer.getLocalPort(), "default");
                Future<MetChannel> connection = client.connect(target);
                try (Socket accepted = silentPeer.accept()) {
                    assertFalse(connection.isDone());

                    // TCP 已连接仍不能视为协议就绪，最终应报告握手超时。
                    try {
                        connection.get(2, TimeUnit.SECONDS);
                        fail();
                    } catch (ExecutionException expected) {
                        assertTrue(expected.getCause() instanceof TimeoutException);
                    }
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void cancellingHandshakeClosesOutgoingSocket() throws Exception {
        // 原始对端读取本端握手，保持连接 future 尚未完成。
        try (ServerSocket peer = new ServerSocket(0)) {
            peer.setSoTimeout(2000);
            ConnectorConfig clientSettings = config("rsf", 1);
            try (Endpoint client = new Endpoint(clientSettings, new Inbox())) {
                InterAddress target = new InterAddress("rsf", "127.0.0.1", peer.getLocalPort(), "default");
                Future<MetChannel> connecting = client.connect(target);
                try (Socket accepted = peer.accept()) {
                    accepted.setSoTimeout(2000);
                    assertTrue(accepted.getInputStream().read() >= 0);
                    assertFalse(connecting.isDone());

                    // 取消尚未完成的握手，原始对端必须读到连接结束。
                    assertTrue(connecting.cancel());
                    byte[] bytes = new byte[128];
                    while (accepted.getInputStream().read(bytes) != -1) {
                        // 读完取消前已写出的握手，随后必须观察到 EOF。
                    }
                    assertTrue(connecting.isCancelled());
                }
            }
        }
    }

    @Test
    public void requestExchangeDoesNotScheduleConnectorTimers() throws Exception {
        // 握手后停用连接器定时器，验证请求期限归属上层 RPC。
        Inbox received = new Inbox();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest incoming = new ReceivedRequest(channel, id);
                ResponsePayload reply = response(incoming, "reply");
                channel.sendData(reply);
            }
        };
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, received)) {
                client.bind().get(2, TimeUnit.SECONDS);
                MetChannel session = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);

                // 业务收发不依赖已停止的连接器定时器。
                this.timersStopped = true;
                for (long id = 1; id <= 2; id++) {
                    RequestPayload request = request(id, "no request timer");
                    assertSame(session, session.sendData(request).get(2, TimeUnit.SECONDS));
                    ResponsePayload returned = received.expect(id).get(2, TimeUnit.SECONDS);
                    assertEquals("reply", returned.getReturnData());
                    request.completion().get(2, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    public void assembledReceiverHandlesEveryListenerAndOutgoingConnection() throws Exception {
        // 将同一接收者接入两个独立监听端点。
        Inbox incoming = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest exchange = new ReceivedRequest(channel, id);
                String value = (String) request.getParameterValues().get(0);
                exchange.channel.sendData(response(exchange, value));
            }
        };
        // 同一接收者接入两个真实监听端点，分别验证连接和响应。
        ConnectorConfig firstServerSettings = config("rsf", port());
        try (Endpoint firstServer = new Endpoint(firstServerSettings, incoming)) {
            MetListen firstListen = firstServer.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig secondServerSettings = config("rsf", port());
            try (Endpoint secondServer = new Endpoint(secondServerSettings, incoming)) {
                MetListen secondListen = secondServer.bind().get(2, TimeUnit.SECONDS);
                assertNotEquals(firstListen.getBindAddress(), secondListen.getBindAddress());
                Inbox received = new Inbox();
                ConnectorConfig clientSettings = config("rsf", port());
                try (Endpoint client = new Endpoint(clientSettings, received)) {
                    // 两个监听入口均能回送请求，每次主动连接都获得独立通道。
                    MetChannel first = client.connect(firstListen.getBindAddress()).get(2, TimeUnit.SECONDS);
                    assertNotSame(first, client.connect(firstListen.getBindAddress()).get(2, TimeUnit.SECONDS));
                    first.sendData(request(1, "first")).get(2, TimeUnit.SECONDS);
                    ResponsePayload firstReply = received.expect(1).get(2, TimeUnit.SECONDS);
                    assertEquals("first", firstReply.getReturnData());
                    MetChannel second = client.connect(secondListen.getBindAddress()).get(2, TimeUnit.SECONDS);
                    assertNotSame(first, second);
                    second.sendData(request(2, "second")).get(2, TimeUnit.SECONDS);
                    ResponsePayload secondReply = received.expect(2).get(2, TimeUnit.SECONDS);
                    assertEquals("second", secondReply.getReturnData());
                    assertTrue(incoming.results.isEmpty());
                }
            }
        }
    }

    @Test
    public void sourceChannelsSeparateIdenticalIdsFromDifferentClients() throws Exception {
        // 两个客户端使用相同请求 ID，服务端必须按来源通道隔离。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                assertEquals(request.getRequestID(), id);
                requests.add(new ReceivedRequest(channel, id));
            }
        };
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            Inbox firstInbox = new Inbox();
            Inbox secondInbox = new Inbox();
            ConnectorConfig firstClientSettings = config("rsf", port());
            try (Endpoint firstClient = new Endpoint(firstClientSettings, firstInbox)) {
                ConnectorConfig secondClientSettings = config("rsf", port());
                try (Endpoint secondClient = new Endpoint(secondClientSettings, secondInbox)) {
                    InterAddress serviceAddress = server.connector.getBindAddress();
                    MetChannel firstClientChannel = firstClient.connect(serviceAddress).get(2, TimeUnit.SECONDS);
                    MetChannel secondClientChannel = secondClient.connect(serviceAddress).get(2, TimeUnit.SECONDS);

                    // 按相反顺序回送两个客户端的结果，不能因相同 ID 串线。
                    firstClientChannel.sendData(request(1, "first")).get(2, TimeUnit.SECONDS);
                    ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
                    secondClientChannel.sendData(request(1, "second")).get(2, TimeUnit.SECONDS);
                    ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
                    assertNotNull(first);
                    assertNotNull(second);
                    assertNotSame(first.channel, second.channel);
                    assertEquals(first.id, second.id);
                    ResponsePayload secondResponse = response(second, "second");
                    MetChannel secondWritten = second.channel.sendData(secondResponse).get(2, TimeUnit.SECONDS);
                    assertSame(second.channel, secondWritten);
                    ResponsePayload firstResponse = response(first, "first");
                    MetChannel firstWritten = first.channel.sendData(firstResponse).get(2, TimeUnit.SECONDS);
                    assertSame(first.channel, firstWritten);
                    ResponsePayload firstReply = firstInbox.expect(1).get(2, TimeUnit.SECONDS);
                    assertEquals("first", firstReply.getReturnData());
                    ResponsePayload secondReply = secondInbox.expect(1).get(2, TimeUnit.SECONDS);
                    assertEquals("second", secondReply.getReturnData());
                    assertSame(firstClientChannel, firstInbox.sources.get(1L));
                    assertSame(secondClientChannel, secondInbox.sources.get(1L));
                }
            }
        }
    }

    @Test
    public void peerClosureFailureKeepsTheSourceChannel() throws Exception {
        // 发起一个等待响应的请求，并记录其出站通道。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                requests.add(new ReceivedRequest(channel, id));
            }
        };
        Inbox received = new Inbox();
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, received)) {
                MetChannel outgoing = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                outgoing.sendData(request(2, "pending")).get(2, TimeUnit.SECONDS);
                assertNotNull(requests.poll(2, TimeUnit.SECONDS));

                // 对端关闭导致的失败通知仍关联原来的出站通道。
                server.connector.close();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    received.expect(2).get(2, TimeUnit.SECONDS);
                });
                assertNotNull(failure.getCause());
                assertSame(outgoing, received.sources.get(2L));
            }
        }
    }

    @Test
    public void acceptedTcpChannelCanSendRequestsWhileBothDirectionsUseSameId() throws Exception {
        // 同一 TCP 连接的两端同时使用相同请求 ID 发起调用。
        BlockingQueue<ReceivedRequest> serverRequests = new LinkedBlockingQueue<>();
        BlockingQueue<ReceivedRequest> clientRequests = new LinkedBlockingQueue<>();
        Inbox serverInbox = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                serverRequests.add(new ReceivedRequest(channel, id));
            }
        };
        Inbox clientInbox = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                clientRequests.add(new ReceivedRequest(channel, id));
            }
        };
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverInbox)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, clientInbox)) {
                MetChannel outgoing = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);

                // 已接受通道可反向发起请求，双向 ID 相同仍分别匹配。
                outgoing.sendData(request(1, "client request")).get(2, TimeUnit.SECONDS);
                ReceivedRequest atServer = serverRequests.poll(2, TimeUnit.SECONDS);
                assertNotNull(atServer);
                RequestPayload reverseRequest = request(1, "server request");
                MetChannel reverseWritten = atServer.channel.sendData(reverseRequest).get(2, TimeUnit.SECONDS);
                assertSame(atServer.channel, reverseWritten);
                ReceivedRequest atClient = clientRequests.poll(2, TimeUnit.SECONDS);
                assertNotNull(atClient);
                assertSame(outgoing, atClient.channel);
                atClient.channel.sendData(response(atClient, "client response")).get(2, TimeUnit.SECONDS);
                atServer.channel.sendData(response(atServer, "server response")).get(2, TimeUnit.SECONDS);
                ResponsePayload clientReply = serverInbox.expect(1).get(2, TimeUnit.SECONDS);
                assertEquals("client response", clientReply.getReturnData());
                ResponsePayload serverReply = clientInbox.expect(1).get(2, TimeUnit.SECONDS);
                assertEquals("server response", serverReply.getReturnData());
                assertSame(atServer.channel, serverInbox.sources.get(1L));
                assertSame(outgoing, clientInbox.sources.get(1L));
            }
        }
    }

    @Test
    public void unknownReplyIdDoesNotConsumeThePendingRequest() throws Exception {
        // 准备一个待应答请求，注入不匹配的响应 ID。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox received = new Inbox();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest received = new ReceivedRequest(channel, id);
                received.payload = request;
                requests.add(received);
            }
        };
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, received)) {
                MetChannel session = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                session.sendData(request(7, "call")).get(2, TimeUnit.SECONDS);
                ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(incoming);

                // 未登记的请求 ID 不能占用合法请求的应答机会。
                ResponsePayload wrong = response(incoming, "wrong");
                wrong.setRequestID(99);
                assertReplyRejected(incoming.channel, wrong);
                incoming.channel.sendData(response(incoming, "valid")).get(2, TimeUnit.SECONDS);
                ResponsePayload returned = received.expect(7).get(2, TimeUnit.SECONDS);
                assertEquals("valid", returned.getReturnData());
            }
        }
    }

    @Test
    public void intermediateReplyLeavesOneFinalReplyAvailable() throws Exception {
        // 准备一个允许中间应答的普通请求。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox received = new Inbox();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest received = new ReceivedRequest(channel, id);
                received.payload = request;
                requests.add(received);
            }
        };
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, received)) {
                MetChannel session = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                session.sendData(request(7, "call")).get(2, TimeUnit.SECONDS);
                ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(incoming);

                // 中间应答保持请求有效，随后仍允许一次最终应答。
                ResponsePayload accepted = response(incoming, "accepted");
                accepted.setStatus(ProtocolStatus.Accept);
                MetChannel written = incoming.channel.sendData(accepted).get(2, TimeUnit.SECONDS);
                assertSame(incoming.channel, written);
                ResponsePayload acknowledgement = received.expect(7).get(2, TimeUnit.SECONDS);
                assertEquals(ProtocolStatus.Accept, acknowledgement.getStatus());
                received.results.remove(7L);
                incoming.channel.sendData(response(incoming, "final")).get(2, TimeUnit.SECONDS);
                ResponsePayload returned = received.expect(7).get(2, TimeUnit.SECONDS);
                assertEquals("final", returned.getReturnData());
                assertReplyRejected(incoming.channel, response(incoming, "duplicate"));
            }
        }
    }

    @Test
    public void concurrentFinalRepliesAllowOnlyOneSuccessfulWrite() throws Exception {
        // 准备一个请求，让多个工作线程竞争最终应答。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox received = new Inbox();
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest received = new ReceivedRequest(channel, id);
                received.payload = request;
                requests.add(received);
            }
        };
        ConnectorConfig serverSettings = config("rsf", port());
        try (Endpoint server = new Endpoint(serverSettings, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, received)) {
                MetChannel session = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                session.sendData(request(7, "call")).get(2, TimeUnit.SECONDS);
                ReceivedRequest incoming = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(incoming);

                // 竞争同一次最终回复，只有一个写入 future 可以成功。
                ExecutorService workers = Executors.newFixedThreadPool(4);
                BlockingQueue<Future<MetChannel>> writes = new LinkedBlockingQueue<>();
                CountDownLatch prepared = new CountDownLatch(4);
                CountDownLatch start = new CountDownLatch(1);
                try {
                    for (int i = 0; i < 20; i++) {
                        workers.execute(() -> {
                            // 先等四个工作线程到齐，再一起竞争同一个请求的最终应答。
                            prepared.countDown();
                            try {
                                assertTrue(start.await(2, TimeUnit.SECONDS));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                            ResponsePayload finalResponse = response(incoming, "final");
                            writes.add(incoming.channel.sendData(finalResponse));
                        });
                    }
                    assertTrue(prepared.await(2, TimeUnit.SECONDS));
                    start.countDown();

                    // 汇总各次写入结果，失败原因必须是应答机会已经终结。
                    int successful = 0;
                    for (int i = 0; i < 20; i++) {
                        Future<MetChannel> writing = writes.poll(2, TimeUnit.SECONDS);
                        assertNotNull(writing);
                        try {
                            assertSame(incoming.channel, writing.get(2, TimeUnit.SECONDS));
                            successful++;
                        } catch (ExecutionException expected) {
                            assertTrue(expected.getCause() instanceof IllegalStateException);
                        }
                    }
                    assertEquals(1, successful);
                    ResponsePayload finalReply = received.expect(7).get(2, TimeUnit.SECONDS);
                    assertEquals("final", finalReply.getReturnData());
                } finally {
                    workers.shutdownNow();
                }
            }
        }
    }

    @Test
    public void messageAcknowledgementTerminatesTheReply() throws Exception {
        // 消息请求收到 Accept 后应立即终结应答机会。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox received = new Inbox();
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        options.put("maxFrameSize", "1024");
        options.put("listenType", "tcp");
        options.put("protocol", "rsf");
        InterAddress localAddress = config("rsf", port()).address();
        String protocolName = options.getOrDefault("protocol", "rsf");
        ProtocolConfig mount = new ProtocolConfig("server", "rsf", protocolName, options);
        List<ProtocolConfig> serverConfigMounts = Collections.singletonList(mount);
        ConnectorConfig serverConfig = new ConnectorConfig("server", localAddress, options, serverConfigMounts, true);
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest received = new ReceivedRequest(channel, id);
                received.payload = request;
                requests.add(received);
            }
        };
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, received)) {
                MetChannel session = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);

                // 发出消息并回送确认，第二次应答必须被拒绝。
                RequestPayload message = request(1, "message");
                message.setMessage(true);
                session.sendData(message).get(2, TimeUnit.SECONDS);
                ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(first);
                ResponsePayload accepted = response(first, "ack");
                accepted.setStatus(ProtocolStatus.Accept);
                first.channel.sendData(accepted).get(2, TimeUnit.SECONDS);
                received.expect(1).get(2, TimeUnit.SECONDS);
                assertReplyRejected(first.channel, response(first, "duplicate"));
            }
        }
    }

    @Test
    public void failedResponseEncodingTerminatesTheReply() throws Exception {
        // 配置较小帧上限，准备无法写出的超长业务响应。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox received = new Inbox();
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        options.put("maxFrameSize", "1024");
        options.put("listenType", "tcp");
        options.put("protocol", "rsf");
        InterAddress localAddress = config("rsf", port()).address();
        String protocolName = options.getOrDefault("protocol", "rsf");
        ProtocolConfig mount = new ProtocolConfig("server", "rsf", protocolName, options);
        List<ProtocolConfig> serverConfigMounts = Collections.singletonList(mount);
        ConnectorConfig serverConfig = new ConnectorConfig("server", localAddress, options, serverConfigMounts, true);
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest received = new ReceivedRequest(channel, id);
                received.payload = request;
                requests.add(received);
            }
        };
        try (Endpoint server = new Endpoint(serverConfig, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, received)) {
                MetChannel session = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);

                // 超长响应写入失败后，也不能再次应答同一个请求。
                session.sendData(request(2, "bad encoding")).get(2, TimeUnit.SECONDS);
                ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(second);
                ResponsePayload invalid = response(second, "invalid");
                invalid.setReturnData(new String(new char[8192]));
                try {
                    second.channel.sendData(invalid).get(2, TimeUnit.SECONDS);
                    fail("Oversized frame must fail the write future");
                } catch (ExecutionException expected) {
                    assertNotNull(expected.getCause());
                }
                assertReplyRejected(second.channel, response(second, "late"));
            }
        }
    }

    @Test
    public void completedRequestReleasesIncomingCapacity() throws Exception {
        // 仅允许一个入站请求，模拟其 RPC 期限结束。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox received = new Inbox();
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        options.put("maxPendingRequests", "1");
        options.put("listenType", "tcp");
        options.put("protocol", "rsf");
        InterAddress localAddress = config("rsf", port()).address();
        String protocolName = options.getOrDefault("protocol", "rsf");
        ProtocolConfig mount = new ProtocolConfig("server", "rsf", protocolName, options);
        List<ProtocolConfig> configMounts = Collections.singletonList(mount);
        ConnectorConfig config = new ConnectorConfig("server", localAddress, options, configMounts, true);
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest received = new ReceivedRequest(channel, id);
                received.payload = request;
                requests.add(received);
            }
        };
        try (Endpoint server = new Endpoint(config, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, received)) {
                MetChannel session = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);

                // 先耗尽容量，再以请求完成事件释放它。
                RequestPayload firstRequest = request(1, "expire");
                firstRequest.setClientTimeout(31337);
                session.sendData(firstRequest).get(2, TimeUnit.SECONDS);
                ReceivedRequest first = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(first);
                first.payload.complete(new TimeoutException("RPC timed out"));
                firstRequest.complete(new TimeoutException("RPC timed out"));
                assertReplyRejected(first.channel, response(first, "late"));

                // 旧请求完成后，下一个请求应能取得唯一容量并正常回复。
                RequestPayload nextRequest = request(2, "next");
                nextRequest.setClientTimeout(31337);
                session.sendData(nextRequest).get(2, TimeUnit.SECONDS);
                ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(second);
                second.channel.sendData(response(second, "next")).get(2, TimeUnit.SECONDS);
                received.expect(2).get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void oldCompletionCannotRemoveAReusedRequestId() throws Exception {
        // 首次请求已回复后复用相同 ID，再触发旧请求完成回调。
        BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
        Inbox received = new Inbox();
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        options.put("maxPendingRequests", "1");
        options.put("listenType", "tcp");
        options.put("protocol", "rsf");
        InterAddress localAddress = config("rsf", port()).address();
        String protocolName = options.getOrDefault("protocol", "rsf");
        ProtocolConfig mount = new ProtocolConfig("server", "rsf", protocolName, options);
        List<ProtocolConfig> configMounts = Collections.singletonList(mount);
        ConnectorConfig config = new ConnectorConfig("server", localAddress, options, configMounts, true);
        Inbox serverReceiver = new Inbox() {
            public void onRequest(MetChannel channel, long id, RequestPayload request) {
                ReceivedRequest received = new ReceivedRequest(channel, id);
                received.payload = request;
                requests.add(received);
            }
        };
        try (Endpoint server = new Endpoint(config, serverReceiver)) {
            server.bind().get(2, TimeUnit.SECONDS);
            ConnectorConfig clientSettings = config("rsf", port());
            try (Endpoint client = new Endpoint(clientSettings, received)) {
                MetChannel session = client.connect(server.connector.getBindAddress()).get(2, TimeUnit.SECONDS);
                RequestPayload nextRequest = request(2, "next");
                nextRequest.setClientTimeout(31337);
                session.sendData(nextRequest).get(2, TimeUnit.SECONDS);
                ReceivedRequest second = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(second);
                second.channel.sendData(response(second, "next")).get(2, TimeUnit.SECONDS);
                received.expect(2).get(2, TimeUnit.SECONDS);

                // 复用同一个 ID 后，旧完成事件不能删除新请求的应答状态。
                received.results.remove(2L);
                session.sendData(request(2, "reused")).get(2, TimeUnit.SECONDS);
                ReceivedRequest reused = requests.poll(2, TimeUnit.SECONDS);
                assertNotNull(reused);
                second.payload.complete(new TimeoutException("late completion"));
                reused.channel.sendData(response(reused, "reused")).get(2, TimeUnit.SECONDS);
                ResponsePayload reusedReply = received.expect(2).get(2, TimeUnit.SECONDS);
                assertEquals("reused", reusedReply.getReturnData());
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
        ClassLoader loader = TcpTransportTest.class.getClassLoader();
        // 仅提供连接器装配必需的配置，其他框架调用直接报告误用。
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
        // 协议使用真实 Java 序列化器，不模拟 RSF 编解码或请求关联。
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
        Class<?>[] contextInterfaces = { MetContext.class };
        return (MetContext) Proxy.newProxyInstance(loader, contextInterfaces, contextHandler);
    }

    private static void subscribe(ConnectorManager manager, ReceivedListener receiver) {
        // 正式初始化后，将分发数据按类型交给本次测试的接收者。
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

    /** 正式 SPI 装配的测试端点，资源关闭由每个用例的 try 管理。 */
    private final class Endpoint implements AutoCloseable {
        private final ConnectorConfig  config;
        private final ConnectorManager manager;
        private       MetConnector     connector;

        private Endpoint(ConnectorConfig config, ReceivedListener listener) {
            this.config = config;
            // 工厂按生产 SPI 装配协议与传输；这里只控制定时器故障。
            MetContext context = runtimeContext(config);
            EndpointConnectorFactory factory = new EndpointConnectorFactory();
            factory.listenTypes(context.getClassLoader());
            factory.prepare(config);
            this.manager = new ConnectorManager(context, factory) {
                @Override
                public Cancellable schedule(Runnable task, long delayMillis) {
                    if (TcpTransportTest.this.timersStopped) {
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
