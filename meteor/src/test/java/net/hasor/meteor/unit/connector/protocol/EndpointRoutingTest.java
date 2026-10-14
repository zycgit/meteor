/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.protocol;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ConnectorManager;
import net.hasor.meteor.connector.ConnectorSubscriber;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.connector.MetListen;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.support.connector.protocol.EndpointTestProtocols.*;
import net.hasor.meteor.support.connector.protocol.SocketTestSupport;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import org.junit.Test;
import static org.junit.Assert.*;

/** 通过真实 socket 验证端点路由和协议视图的内部契约。 */
public class EndpointRoutingTest {
    @Test(timeout = 15000)
    public void httpPartitionsSharePortAndConnectionWithoutMixingReplies() throws Exception {
        // 同一 HTTP 监听与连接上按挂载路径选择两个协议。
        ConnectorConfig config = endpointConfig("http", HttpA.class, HttpB.class);
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            MetListen listen = endpoint.bind("shared").get(3, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                sendHttp(socket, "/a", "first");
                sendHttp(socket, "/b", "second");
                Delivery a = inbox.take();
                Delivery b = inbox.take();

                // 协议视图独立维护关联号，响应仍遵守物理连接的请求顺序。
                assertEquals("a", a.channel.getRemote().getSchema());
                assertEquals("b", b.channel.getRemote().getSchema());
                assertEquals(a.id, b.id); // 关联号归属各自的协议视图。
                assertNotEquals(a.channel.getChannelId(), b.channel.getChannelId());
                assertSame(a.channel.getConnector(), b.channel.getConnector());
                Future<MetChannel> later = b.channel.sendData(response(b.id, "second"));
                assertFalse(later.isDone());
                a.channel.sendData(response(a.id, "first")).get(3, TimeUnit.SECONDS);
                assertEquals("A:first", readHttp(socket, 200));
                assertEquals("B:second", readHttp(socket, 200));
                later.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 10000)
    public void closingOneHttpPartitionKeepsOtherPartitionUsable() throws Exception {
        // 在同一连接上建立两个协议视图，并完成首次请求。
        ConnectorConfig config = endpointConfig("http", HttpA.class, HttpB.class);
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            MetListen listen = endpoint.bind("shared").get(3, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                sendHttp(socket, "/a", "first");
                Delivery a = inbox.take();
                a.channel.sendData(response(a.id, "first")).get(3, TimeUnit.SECONDS);
                assertEquals("A:first", readHttp(socket, 200));
                sendHttp(socket, "/b", "second");
                Delivery b = inbox.take();
                b.channel.sendData(response(b.id, "second")).get(3, TimeUnit.SECONDS);
                assertEquals("B:second", readHttp(socket, 200));

                // 关闭 A 视图不会关闭 B 视图及其共享的物理连接。
                a.channel.close().get(3, TimeUnit.SECONDS);
                assertFalse(a.channel.isActive());
                assertTrue(b.channel.isActive());
                sendHttp(socket, "/b", "still open");
                Delivery next = inbox.take();
                assertSame(b.channel, next.channel);
                next.channel.sendData(response(next.id, "still open")).get(3, TimeUnit.SECONDS);
                assertEquals("B:still open", readHttp(socket, 200));
            }
        }
    }

    @Test(timeout = 10000)
    public void unknownHttpRouteReturns404AndKeepAliveRemainsUsable() throws Exception {
        // 未知挂载路径返回 404，且不污染当前 keep-alive 连接。
        ConnectorConfig config = endpointConfig("http", HttpA.class, HttpB.class);
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            MetListen listen = endpoint.bind("shared").get(3, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                sendHttp(socket, "/absent", "bad");
                assertEquals("", readHttp(socket, 404));

                // 后续合法路径仍被正确路由，查询参数不参与挂载匹配。
                sendHttp(socket, "/a?query=1", "good");
                Delivery request = inbox.take();
                request.channel.sendData(response(request.id, "good")).get(3, TimeUnit.SECONDS);
                assertEquals("A:good", readHttp(socket, 200));
            }
        }
    }

    @Test(timeout = 10000)
    public void closingSharedEndpointDrainsSubmittedResponseAcrossPartitions() throws Exception {
        // A 的应答尚未提交，B 的应答已排入共享连接的有序队列。
        ConnectorConfig config = endpointConfig("http", HttpA.class, HttpB.class);
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            MetListen listen = endpoint.bind("shared").get(3, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                sendHttp(socket, "/a", "unanswered");
                sendHttp(socket, "/b", "submitted");
                inbox.take();
                Delivery b = inbox.take();
                Future<MetChannel> writing = b.channel.sendData(response(b.id, "submitted"));

                // 关闭端点时补齐未应答槽位，再排空已经提交的 B 应答。
                ExecutorService lifecycle = Executors.newSingleThreadExecutor();
                try {
                    FutureTask<Void> closing = new FutureTask<>(endpoint::close, null);
                    lifecycle.execute(closing);
                    assertEquals("closing", readHttp(socket, 503));
                    assertEquals("B:submitted", readHttp(socket, 200));
                    writing.get(3, TimeUnit.SECONDS);
                    closing.get(3, TimeUnit.SECONDS);
                    assertEquals(-1, socket.getInputStream().read());
                } finally {
                    lifecycle.shutdownNow();
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void outboundSchemeASelectsProtocolWithoutBindingLocally() throws Exception {
        // 地址 scheme 选择 A 协议，发起连接不创建本地监听。
        ConnectorConfig serverConfig = endpointConfig("http", HttpA.class, HttpB.class);
        ConnectorConfig clientConfig = endpointConfig("http", HttpA.class, HttpB.class);
        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            Inbox serverInbox = new Inbox();
            Inbox clientInbox = new Inbox();
            server.subscribe(serverInbox);
            client.subscribe(clientInbox);
            MetListen listen = server.bind("shared").get(3, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            String scheme = "a";
            InterAddress target = new InterAddress(scheme, "127.0.0.1", port, "test");
            MetChannel channel = client.connect(target).get(3, TimeUnit.SECONDS);
            assertEquals(scheme, channel.getRemote().getSchema());
            assertTrue(client.find("shared").getListenList().isEmpty());

            // 请求及响应均沿所选协议视图传输，保留调用方请求号。
            RequestPayload request = new RequestPayload();
            request.setRequestID(33);
            request.setTargetMethod("outbound");
            channel.sendData(request).get(3, TimeUnit.SECONDS);
            Delivery incoming = serverInbox.take();
            incoming.channel.sendData(response(incoming.id, "outbound")).get(3, TimeUnit.SECONDS);
            Delivery answer = clientInbox.take();
            assertEquals(33, answer.id);
            String expected = scheme.toUpperCase(Locale.ROOT) + ":outbound";
            ResponsePayload response = (ResponsePayload) answer.payload;
            assertEquals(expected, response.getReturnData());
        }
    }

    @Test(timeout = 10000)
    public void outboundSchemeBSelectsProtocolWithoutBindingLocally() throws Exception {
        // 地址 scheme 选择 B 协议，发起连接不创建本地监听。
        ConnectorConfig serverConfig = endpointConfig("http", HttpA.class, HttpB.class);
        ConnectorConfig clientConfig = endpointConfig("http", HttpA.class, HttpB.class);
        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            Inbox serverInbox = new Inbox();
            Inbox clientInbox = new Inbox();
            server.subscribe(serverInbox);
            client.subscribe(clientInbox);
            MetListen listen = server.bind("shared").get(3, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            String scheme = "b";
            InterAddress target = new InterAddress(scheme, "127.0.0.1", port, "test");
            MetChannel channel = client.connect(target).get(3, TimeUnit.SECONDS);
            assertEquals(scheme, channel.getRemote().getSchema());
            assertTrue(client.find("shared").getListenList().isEmpty());

            // 请求及响应均沿所选协议视图传输，保留调用方请求号。
            RequestPayload request = new RequestPayload();
            request.setRequestID(33);
            request.setTargetMethod("outbound");
            channel.sendData(request).get(3, TimeUnit.SECONDS);
            Delivery incoming = serverInbox.take();
            incoming.channel.sendData(response(incoming.id, "outbound")).get(3, TimeUnit.SECONDS);
            Delivery answer = clientInbox.take();
            assertEquals(33, answer.id);
            String expected = scheme.toUpperCase(Locale.ROOT) + ":outbound";
            ResponsePayload response = (ResponsePayload) answer.payload;
            assertEquals(expected, response.getReturnData());
        }
    }

    @Test(timeout = 10000)
    public void tcpSelectsFragmentedPrefixAndKeepsSelectedProtocol() throws Exception {
        // TCP 探测器等待完整前缀，选中后固定当前连接的协议。
        ConnectorConfig config = endpointConfig("tcp", WireA.class, WireB.class);
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            MetListen listen = endpoint.bind("shared").get(3, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write('B');
                socket.getOutputStream().flush();
                assertNull(inbox.messages.poll(100, TimeUnit.MILLISECONDS));
                socket.getOutputStream().write("Bhello\n".getBytes(StandardCharsets.UTF_8));
                Delivery request = inbox.take();
                assertEquals("b", request.channel.getRemote().getSchema());
                request.channel.sendData(response(request.id, "reply")).get(3, TimeUnit.SECONDS);
                assertEquals("BBreply", line(socket.getInputStream()));

                // 后续报文即使带另一个协议的前缀，也不重新探测或切换视图。
                socket.getOutputStream().write("AAlater\n".getBytes(StandardCharsets.UTF_8));
                assertSame(request.channel, inbox.take().channel);
            }
        }
    }

    @Test(timeout = 10000)
    public void tcpRejectsUnknownPrefixWithoutDeliveringRpcMessages() throws Exception {
        // 未知 TCP 前缀关闭连接，不把报文交给 RPC 订阅器。
        ConnectorConfig config = endpointConfig("tcp", WireA.class, WireB.class);
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            MetListen listen = endpoint.bind("shared").get(3, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write("XX\n".getBytes(StandardCharsets.UTF_8));
                assertEquals(-1, socket.getInputStream().read());
                assertTrue(inbox.messages.isEmpty());
            }
        }
    }

    @Test(timeout = 10000)
    public void udpPartitionsCompleteDatagramsAndClosingOneViewKeepsPeerUsable() throws Exception {
        // 同一 UDP 对端按完整报文选择协议，关闭一个视图不影响另一个。
        ConnectorConfig config = endpointConfig("udp", WireA.class, WireB.class);
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            MetListen listen = endpoint.bind("shared").get(3, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            // 先绑定服务端，再创建独立的 UDP 客户端。
            try (DatagramSocket peer = new DatagramSocket()) {
                peer.setSoTimeout(3000);
                sendDatagram(peer, port, "AAone\n");
                Delivery a = inbox.take();
                sendDatagram(peer, port, "BBtwo\n");
                Delivery b = inbox.take();
                assertNotSame(a.channel, b.channel);

                // 关闭 A 视图后，B 仍可通过同一个物理 UDP socket 返回应答。
                a.channel.close().get(3, TimeUnit.SECONDS);
                assertTrue(b.channel.isActive());
                b.channel.sendData(response(b.id, "reply")).get(3, TimeUnit.SECONDS);
                DatagramPacket response = new DatagramPacket(new byte[1024], 1024);
                peer.receive(response);
                String reply = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8);
                assertEquals("BBreply\n", reply);
            }
        }
    }

    @Test(timeout = 10000)
    public void ambiguousTcpProtocolIsRejected() throws Exception {
        // 两个挂载都接受同一前缀时拒绝连接，避免不确定路由。
        ConnectorConfig config = endpointConfig("tcp", WireA.class, WireA.class);
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            MetListen listen = endpoint.bind("shared").get(3, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write("AA\n".getBytes(StandardCharsets.UTF_8));
                assertEquals(-1, socket.getInputStream().read());
                assertTrue(inbox.messages.isEmpty());
            }
        }
    }

    @Test(timeout = 10000)
    public void incompleteProtocolProbeCannotBufferBeyondLimit() throws Exception {
        // 始终等待更多字节的探测器也必须遵守前缀缓冲上限。
        ConnectorConfig config = endpointConfig("tcp", IncompleteProbe.class, IncompleteProbe.class);
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            MetListen listen = endpoint.bind("shared").get(3, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(new byte[256]);
                assertEquals(-1, socket.getInputStream().read());
                assertTrue(inbox.messages.isEmpty());
            }
        }
    }

    @Test(timeout = 10000)
    public void outgoingTcpPreselectsTargetProtocolBeforeFirstWrite() throws Exception {
        // 主动连接在首个报文写入前按目标地址选定协议。
        ConnectorConfig serverConfig = endpointConfig("tcp", WireA.class, WireB.class);
        ConnectorConfig clientConfig = endpointConfig("tcp", WireA.class, WireB.class);
        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            Inbox serverInbox = new Inbox();
            Inbox clientInbox = new Inbox();
            server.subscribe(serverInbox);
            client.subscribe(clientInbox);
            MetListen listen = server.bind("shared").get(3, TimeUnit.SECONDS);
            int port = listen.getBindAddress().getPort();
            InterAddress target = new InterAddress("b", "127.0.0.1", port, "test");
            MetChannel channel = client.connect(target).get(3, TimeUnit.SECONDS);
            assertTrue(client.find("shared").getListenList().isEmpty());
            channel.sendData(response(1, "first write")).get(3, TimeUnit.SECONDS);
            Delivery firstWrite = serverInbox.take();
            assertEquals("b", firstWrite.channel.getRemote().getSchema());
        }
    }

    private static void sendDatagram(DatagramSocket peer, int port, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        peer.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
    }

    private static ResponsePayload response(long id, String value) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(id);
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        return response;
    }

    private static void sendHttp(Socket socket, String path, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        String requestLine = "POST " + path + " HTTP/1.1\r\n";
        String headers = "Host: localhost\r\nContent-Length: " + bytes.length + "\r\n\r\n";
        socket.getOutputStream().write((requestLine + headers).getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static String line(InputStream stream) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = stream.read()) != -1 && value != '\n') {
            if (value != '\r') {
                line.write(value);
            }
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private static String readHttp(Socket socket, int status) throws Exception {
        // 读取状态行与响应头，按 Content-Length 消费恰好一个响应。
        InputStream stream = socket.getInputStream();
        String statusLine = line(stream);
        assertTrue(statusLine.startsWith("HTTP/1.1 " + status));
        int length = 0;
        String header;
        while (!(header = line(stream)).isEmpty()) {
            if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(header.substring(15).trim());
            }
        }

        byte[] body = new byte[length];
        new DataInputStream(stream).readFully(body);
        return new String(body, StandardCharsets.UTF_8);
    }

    private static final class Delivery {
        final MetChannel channel;
        final long       id;
        final Payload    payload;

        Delivery(MetChannel channel, long id, Payload payload) {
            this.channel = channel;
            this.id = id;
            this.payload = payload;
        }
    }

    private static ConnectorConfig endpointConfig(String transport, Class<?> a, Class<?> b) throws SocketException {
        Map<String, String> options = new HashMap<>();
        options.put("listenType", transport);
        options.put("workerThread", "1");
        String prefix = StringUtils.equals(transport, "udp") ? "datagram" : "";
        String firstProtocol = prefix + a.getSimpleName().toLowerCase(Locale.ROOT);
        String secondProtocol = prefix + b.getSimpleName().toLowerCase(Locale.ROOT);
        // HTTP 按路径分流，TCP/UDP 的协议识别不配置 HTTP 上下文。
        Map<String, String> firstOptions = Map.of();
        Map<String, String> secondOptions = Map.of();
        if (StringUtils.equals(transport, "http")) {
            firstOptions = Map.of("contextPath", "/a///");
            secondOptions = Map.of("contextPath", "/b///");
        }

        ProtocolConfig first = new ProtocolConfig("a", "a", firstProtocol, firstOptions);
        ProtocolConfig second = new ProtocolConfig("b", "b", secondProtocol, secondOptions);
        // UDP 要求显式非零监听端口；TCP 和 HTTP 仍由 bind 分配端口。
        int port = 0;
        if (StringUtils.equals(transport, "udp")) {
            port = SocketTestSupport.availableUdpPort();
        }
        InterAddress address = InterAddress.forBinding(transport, "127.0.0.1", port, "test");
        List<ProtocolConfig> configMounts = Arrays.asList(first, second);
        return new ConnectorConfig("shared", address, options, configMounts, true);
    }

    private static final class Inbox implements ConnectorSubscriber {
        final BlockingQueue<Delivery> messages = new LinkedBlockingQueue<>();

        @Override
        public void onMessage(MetChannel channel, long id, Payload payload) {
            this.messages.add(new Delivery(channel, id, payload));
        }

        Delivery take() throws Exception {
            Delivery message = this.messages.poll(3, TimeUnit.SECONDS);
            assertNotNull("Expected routed message", message);
            return message;
        }
    }
}
