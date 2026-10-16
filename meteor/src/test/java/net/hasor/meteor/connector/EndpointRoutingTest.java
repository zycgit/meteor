/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import net.hasor.example.connector.protocol.EndpointTestProtocols.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 通过真实 socket 验证端点路由和协议视图的内部契约。 */
public class EndpointRoutingTest {
    @Test(timeout = 15000)
    public void httpPartitionsSharePortAndConnectionWithoutMixingReplies() throws Exception {
        // 两个逻辑协议共享 HTTP 地址 scheme，由同一监听上的完整路径选择挂载。
        ConnectorConfig config = endpointConfig("http", HttpA.class, HttpB.class, "http", "http");
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            endpoint.startInbound();
            InterAddress shared = endpoint.getListenAddress("a");
            assertEquals(shared, endpoint.getListenAddress("b"));
            int port = shared.getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                sendHttp(socket, "/a", "first");
                sendHttp(socket, "/b", "second");
                Delivery a = inbox.take();
                Delivery b = inbox.take();

                // 协议视图独立维护关联号，响应仍遵守物理连接的请求顺序。
                assertEquals("http", a.channel.getRemote().getSchema());
                assertEquals("http", b.channel.getRemote().getSchema());
                assertEquals(a.id, b.id); // 关联号归属各自的协议视图。
                assertNotSame(a.channel, b.channel);
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
    public void httpPartitionsRemainUsableOnTheSharedConnection() throws Exception {
        // 在同一连接上依次使用两个协议视图，并继续复用后一个视图。
        ConnectorConfig config = endpointConfig("http", HttpA.class, HttpB.class);
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            endpoint.startInbound();
            int port = endpoint.getListenAddress("a").getPort();
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

                // B 视图及其共享的物理连接可以继续处理下一次请求。
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
            endpoint.startInbound();
            int port = endpoint.getListenAddress("a").getPort();
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
            endpoint.startInbound();
            int port = endpoint.getListenAddress("a").getPort();
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
            server.startInbound();
            int port = server.getListenAddress("a").getPort();
            String scheme = "a";
            InterAddress target = new InterAddress(scheme, "127.0.0.1", port, "test");
            MetChannel channel = client.connect(target).get(3, TimeUnit.SECONDS);
            assertEquals(scheme, channel.getRemote().getSchema());
            assertNull(client.getListenAddress(scheme));

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
            server.startInbound();
            int port = server.getListenAddress("b").getPort();
            String scheme = "b";
            InterAddress target = new InterAddress(scheme, "127.0.0.1", port, "test");
            MetChannel channel = client.connect(target).get(3, TimeUnit.SECONDS);
            assertEquals(scheme, channel.getRemote().getSchema());
            assertNull(client.getListenAddress(scheme));

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
        // 两个逻辑协议共享公开 scheme，TCP 探测器以 mountId 固定当前连接的协议。
        ConnectorConfig config = endpointConfig("tcp", WireA.class, WireB.class, "wire", "wire");
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            endpoint.startInbound();
            int port = endpoint.getListenAddress("a").getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write('B');
                socket.getOutputStream().flush();
                assertNull(inbox.messages.poll(100, TimeUnit.MILLISECONDS));
                socket.getOutputStream().write("Bhello\n".getBytes(StandardCharsets.UTF_8));
                Delivery request = inbox.take();
                assertEquals("wire", request.channel.getRemote().getSchema());
                request.channel.sendData(response(request.id, "reply")).get(3, TimeUnit.SECONDS);
                assertEquals("BBreply", line(socket.getInputStream()));

                // 后续报文即使带另一个协议的前缀，也不重新探测或切换视图。
                socket.getOutputStream().write("AAlater\n".getBytes(StandardCharsets.UTF_8));
                assertSame(request.channel, inbox.take().channel);
            }
        }
    }

    @Test(timeout = 10000)
    public void rsfRequestFrameSelectsRsfOnSharedTcpEndpoint() throws Exception {
        // 用真实 RSF/1 请求作为连接首包，验证共享 TCP 端点选择 RSF 协议。
        byte[] request = fixture("request-v1.bin");
        assertEquals(0x91, request[0] & 0xff);
        ConnectorConfig config = rsfEndpointConfig();
        try (ConnectorManager endpoint = rsfManager(config)) {
            endpoint.init();
            endpoint.startInbound();
            int port = endpoint.getListenAddress("rsf").getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);

                // 首字节完成路由后收到 RSF 握手，再补齐真实请求帧。
                socket.getOutputStream().write(request, 0, 1);
                socket.getOutputStream().flush();
                assertEquals(0xb1, socket.getInputStream().read());
                socket.getOutputStream().write(request, 1, request.length - 1);
                socket.getOutputStream().flush();
            }
        }
    }

    @Test(timeout = 10000)
    public void rsfMessageFrameSelectsRsfOnSharedTcpEndpoint() throws Exception {
        // Message 与普通请求共享 RSF/1 帧结构，仅使用独立的消息帧类型。
        byte[] message = fixture("request-v1.bin");
        message[0] = (byte) 0xa1;
        ConnectorConfig config = rsfEndpointConfig();
        try (ConnectorManager endpoint = rsfManager(config)) {
            endpoint.init();
            endpoint.startInbound();
            int port = endpoint.getListenAddress("rsf").getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);

                // Message 首字节同样应进入 RSF 会话，不能落入其它 TCP 协议。
                socket.getOutputStream().write(message, 0, 1);
                socket.getOutputStream().flush();
                assertEquals(0xb1, socket.getInputStream().read());
                socket.getOutputStream().write(message, 1, message.length - 1);
                socket.getOutputStream().flush();
            }
        }
    }

    @Test(timeout = 10000)
    public void tcpWaitsUntilPendingProbeRejectsThePrefix() throws Exception {
        // RSF 已匹配首字节，另一个协议仍可能匹配时不能提前固定静态路由。
        byte[] request = fixture("request-v1.bin");
        ConnectorConfig config = rsfEndpointConfig("rsfdelayedcandidate");
        try (ConnectorManager endpoint = rsfManager(config)) {
            endpoint.init();
            endpoint.startInbound();
            int port = endpoint.getListenAddress("rsf").getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(200);
                socket.getOutputStream().write(request, 0, 1);
                socket.getOutputStream().flush();
                assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());

                // 第二个字节排除待定协议后，RSF 分支才激活并返回握手。
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(request, 1, 1);
                socket.getOutputStream().flush();
                assertEquals(0xb1, socket.getInputStream().read());
                socket.getOutputStream().write(request, 2, request.length - 2);
                socket.getOutputStream().flush();
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
            endpoint.startInbound();
            int port = endpoint.getListenAddress("a").getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write("XX\n".getBytes(StandardCharsets.UTF_8));
                assertEquals(-1, socket.getInputStream().read());
                assertTrue(inbox.messages.isEmpty());
            }
        }
    }

    @Test(timeout = 10000)
    public void udpPartitionsCompleteDatagramsAndReplyIndependently() throws Exception {
        // 共享公开 scheme 的 UDP 挂载按完整报文选择协议，各自维护独立协议视图。
        ConnectorConfig config = endpointConfig("udp", WireA.class, WireB.class, "wire", "wire");
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            endpoint.startInbound();
            int port = endpoint.getListenAddress("a").getPort();
            // 先绑定服务端，再创建独立的 UDP 客户端。
            try (DatagramSocket peer = new DatagramSocket()) {
                peer.setSoTimeout(3000);
                sendDatagram(peer, port, "AAone\n");
                Delivery a = inbox.take();
                sendDatagram(peer, port, "BBtwo\n");
                Delivery b = inbox.take();
                assertNotSame(a.channel, b.channel);

                // B 视图通过共享物理 UDP socket 独立返回应答。
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
        ConnectorConfig config = endpointConfig("tcp", WireA.class, WireAliasB.class);
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            endpoint.startInbound();
            int port = endpoint.getListenAddress("a").getPort();
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
        ConnectorConfig config = endpointConfig("tcp", IncompleteProbe.class, IncompleteProbeB.class);
        try (ConnectorManager endpoint = SocketTestSupport.manager(config)) {
            endpoint.init();
            Inbox inbox = new Inbox();
            endpoint.subscribe(inbox);
            endpoint.startInbound();
            int port = endpoint.getListenAddress("a").getPort();
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
            server.startInbound();
            int port = server.getListenAddress("b").getPort();
            InterAddress target = new InterAddress("b", "127.0.0.1", port, "test");
            MetChannel channel = client.connect(target).get(3, TimeUnit.SECONDS);
            assertNull(client.getListenAddress("b"));
            channel.sendData(response(1, "first write")).get(3, TimeUnit.SECONDS);
            Delivery firstWrite = serverInbox.take();
            assertEquals("b", firstWrite.channel.getRemote().getSchema());
        }
    }

    private static void sendDatagram(DatagramSocket peer, int port, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        peer.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
    }

    private static byte[] fixture(String name) throws Exception {
        String path = "/rsf-v1-wire-fixtures/" + name;
        try (InputStream input = EndpointRoutingTest.class.getResourceAsStream(path)) {
            assertNotNull("Missing RSF wire fixture " + path, input);
            return input.readAllBytes();
        }
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
        private final MetChannel channel;
        private final long       id;
        private final Payload    payload;

        private Delivery(MetChannel channel, long id, Payload payload) {
            this.channel = channel;
            this.id = id;
            this.payload = payload;
        }
    }

    private static ConnectorConfig endpointConfig(String transport, Class<?> a, Class<?> b) throws SocketException {
        return endpointConfig(transport, a, b, "a", "b");
    }

    private static ConnectorConfig endpointConfig(//
            String transport, Class<?> a, Class<?> b, String firstScheme, String secondScheme) throws SocketException {
        Map<String, String> options = new HashMap<>();
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

        ProtocolConfig first = new ProtocolConfig("a", firstScheme, firstProtocol, firstOptions);
        ProtocolConfig second = new ProtocolConfig("b", secondScheme, secondProtocol, secondOptions);
        // UDP 要求显式非零监听端口；TCP 和 HTTP 仍由 bind 分配端口。
        int port = 0;
        if (StringUtils.equals(transport, "udp")) {
            port = SocketTestSupport.availableUdpPort();
        }
        InterAddress address = InterAddress.forBinding(transport, "127.0.0.1", port, "test");
        List<ProtocolConfig> configMounts = Arrays.asList(first, second);
        return new ConnectorConfig("shared", address, options, configMounts, true);
    }

    private static ConnectorConfig rsfEndpointConfig() {
        return rsfEndpointConfig("wireb");
    }

    private static ConnectorConfig rsfEndpointConfig(String alternateProtocol) {
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        ProtocolConfig rsf = new ProtocolConfig("rsf", "rsf", "rsf", Map.of());
        ProtocolConfig other = new ProtocolConfig("b", "b", alternateProtocol, Map.of());
        InterAddress address = InterAddress.forBinding("tcp", "127.0.0.1", 0, "test");
        return new ConnectorConfig("shared", address, options, Arrays.asList(rsf, other), true);
    }

    private static ConnectorManager rsfManager(ConnectorConfig config) {
        ClassLoader loader = EndpointRoutingTest.class.getClassLoader();
        JavaSerializeCoder coder = new JavaSerializeCoder();
        InvocationHandler settingsHandler = (proxy, method, args) -> {
            if (StringUtils.equals("getConnectorConfigs", method.getName())) {
                return Collections.singletonList(config);
            }
            if (StringUtils.equals("getDefaultTimeout", method.getName())) {
                return 10000;
            }
            throw new AssertionError(method);
        };
        Class<?>[] settingsInterfaces = { MetSettings.class };
        MetSettings settings = (MetSettings) Proxy.newProxyInstance(loader, settingsInterfaces, settingsHandler);
        InvocationHandler contextHandler = (proxy, method, args) -> {
            if (StringUtils.equals("getSettings", method.getName())) {
                return settings;
            }
            if (StringUtils.equals("getClassLoader", method.getName())) {
                return loader;
            }
            if (StringUtils.equals("getSerializeCoder", method.getName())) {
                String serializeType = (String) args[0];
                return StringUtils.equalsIgnoreCase("Java", serializeType) ? coder : null;
            }
            throw new AssertionError(method);
        };
        Class<?>[] contextInterfaces = { MetContext.class };
        MetContext context = (MetContext) Proxy.newProxyInstance(loader, contextInterfaces, contextHandler);

        return new ConnectorManager(context);
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
