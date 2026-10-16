/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import org.junit.Test;
import static net.hasor.example.connector.protocol.UdpTestProtocol.request;
import static net.hasor.example.connector.protocol.UdpTestProtocol.response;
import static org.junit.Assert.*;

/** Verifies that secure UDP endpoint options reach Neta as a DTLS pipeline. */
public class UdpDtlsTransportTest {
    private static final String SERVER_STORE = "classpath:/tls/acceptance/server.p12";
    private static final String CLIENT_STORE = "classpath:/tls/acceptance/client.p12";
    private static final String TRUST_STORE  = "classpath:/tls/acceptance/trust.p12";
    private static final String PASSWORD     = "meteor-test";

    @Test(timeout = 10000)
    public void serverAuthenticationNegotiatesDtlsAndCarriesMessages() throws Exception {
        this.verifyRoundTrip(false);
    }

    @Test(timeout = 10000)
    public void mutualAuthenticationNegotiatesDtlsAndCarriesMessages() throws Exception {
        this.verifyRoundTrip(true);
    }

    @Test(timeout = 10000)
    public void oversizedDtlsDatagramIsRejectedBeforeEncryption() throws Exception {
        // DTLS 一次写入必须保持为一条 UDP 业务报文，不能让 SSLEngine 静默拆成多条消息。
        int port = SocketTestSupport.availableUdpPort();
        String serverObservation = "server-limit-" + port;
        String clientObservation = "client-limit-" + port;
        ConnectorConfig serverConfig = this.serverConfig(port, false, serverObservation);
        ConnectorConfig clientConfig = this.clientConfig(false, clientObservation);
        UdpDtlsObservationPipeline.reset(serverObservation);
        UdpDtlsObservationPipeline.reset(clientObservation);

        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            server.startInbound();
            InterAddress address = server.getListenAddress("testprotocol");
            MetChannel outgoing = client.connect(address).get(3, TimeUnit.SECONDS);
            assertEquals("DTLSv1.2", UdpDtlsObservationPipeline.await(serverObservation));
            assertEquals("DTLSv1.2", UdpDtlsObservationPipeline.await(clientObservation));

            // 编码后的报文超过单个 DTLS record 上限时，写入必须明确失败。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                outgoing.sendData(request(2, "x".repeat(1200))).get(3, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertEquals("Datagram size must be between 1 and 1072", failure.getCause().getMessage());
        }
    }

    @Test(timeout = 10000)
    public void clientGracefulCloseDoesNotHang() throws Exception {
        // 安全 UDP 不能半关闭；本地排空关闭必须在时限内完成并释放连接。
        int port = SocketTestSupport.availableUdpPort();
        String serverObservation = "server-close-" + port;
        String clientObservation = "client-close-" + port;
        ConnectorConfig serverConfig = this.serverConfig(port, false, serverObservation);
        ConnectorConfig clientConfig = this.clientConfig(false, clientObservation);
        UdpDtlsObservationPipeline.reset(serverObservation);
        UdpDtlsObservationPipeline.reset(clientObservation);

        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            server.startInbound();
            InterAddress address = server.getListenAddress("testprotocol");
            client.connect(address).get(3, TimeUnit.SECONDS);
            assertEquals("DTLSv1.2", UdpDtlsObservationPipeline.await(serverObservation));
            assertEquals("DTLSv1.2", UdpDtlsObservationPipeline.await(clientObservation));

            // DTLS 警告使用不可靠数据报；本地关闭只保证完成发送，不保证对端一定收到。
            client.close();
            assertEquals(Boolean.TRUE, UdpDtlsObservationPipeline.awaitClose(clientObservation));
        }
    }

    @Test(timeout = 10000)
    public void writesQueuedDuringHandshakeRemainSeparateDatagrams() throws Exception {
        // 握手完成前连续写入仍要逐条进入 SSLEngine，不能合并为一条 DTLS record。
        int port = SocketTestSupport.availableUdpPort();
        String serverObservation = "server-order-" + port;
        String clientObservation = "client-order-" + port;
        ConnectorConfig serverConfig = this.serverConfig(port, false, serverObservation);
        ConnectorConfig clientConfig = this.clientConfig(false, clientObservation);
        UdpDtlsObservationPipeline.reset(serverObservation);
        UdpDtlsObservationPipeline.reset(clientObservation);

        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            Inbox serverInbox = new Inbox();
            server.subscribe(serverInbox);
            server.startInbound();
            InterAddress address = server.getListenAddress("testprotocol");
            MetChannel outgoing = client.connect(address).get(3, TimeUnit.SECONDS);

            // 不等待握手观察事件，连续提交两条消息以覆盖握手期间的写入排队。
            Future<MetChannel> firstWrite = outgoing.sendData(request(11, "first"));
            Future<MetChannel> secondWrite = outgoing.sendData(request(12, "second"));
            firstWrite.get(3, TimeUnit.SECONDS);
            secondWrite.get(3, TimeUnit.SECONDS);
            RequestPayload first = (RequestPayload) serverInbox.take().payload;
            RequestPayload second = (RequestPayload) serverInbox.take().payload;
            assertEquals("first", first.getTargetMethod());
            assertEquals("second", second.getTargetMethod());
            assertEquals("DTLSv1.2", UdpDtlsObservationPipeline.await(serverObservation));
            assertEquals("DTLSv1.2", UdpDtlsObservationPipeline.await(clientObservation));
        }
    }

    @Test(timeout = 10000)
    public void droppedInitialClientHelloIsRetransmittedBeforeRoundTrip() throws Exception {
        // 真实 UDP 中继只丢第一次 ClientHello，并将同一握手键的后续报文作为重传放行。
        int port = SocketTestSupport.availableUdpPort();
        String serverObservation = "server-loss-" + port;
        String clientObservation = "client-loss-" + port;
        ConnectorConfig serverConfig = this.serverConfig(port, false, serverObservation);
        ConnectorConfig clientConfig = this.handshakeClientConfig(false, clientObservation, 100, 3000);
        UdpDtlsObservationPipeline.reset(serverObservation);
        UdpDtlsObservationPipeline.reset(clientObservation);

        try (ConnectorManager server = SocketTestSupport.manager(serverConfig)) {
            server.init();
            Inbox serverInbox = new Inbox();
            server.subscribe(serverInbox);
            server.startInbound();
            InterAddress serverAddress = server.getListenAddress("testprotocol");

            try (DtlsUdpRelay relay = DtlsUdpRelay.start(serverAddress.toSocketAddress()); //
                 ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
                client.init();
                Inbox clientInbox = new Inbox();
                client.subscribe(clientInbox);

                // 业务写入在握手后完成，证明重传恢复的是完整 DTLS 会话。
                InterAddress relayAddress = new InterAddress(serverAddress.getSchema(), "127.0.0.1", relay.port(), //
                        serverAddress.getFormUnit());
                MetChannel outgoing = client.connect(relayAddress).get(3, TimeUnit.SECONDS);
                outgoing.sendData(request(21, "retransmitted-request")).get(5, TimeUnit.SECONDS);
                Delivery accepted = serverInbox.take();
                accepted.channel.sendData(response(21, "retransmitted-response")).get(3, TimeUnit.SECONDS);
                Delivery returned = clientInbox.take();

                // 握手协议、业务响应和相同 ClientHello 的第二次出现共同确认重传链路。
                assertEquals("DTLSv1.2", UdpDtlsObservationPipeline.await(serverObservation));
                assertEquals("DTLSv1.2", UdpDtlsObservationPipeline.await(clientObservation));
                assertTrue(relay.awaitRetransmission(1, TimeUnit.SECONDS));
                assertTrue(relay.clientHelloAttempts() >= 2);
                assertEquals("retransmitted-response", ((ResponsePayload) returned.payload).getReturnData());
                relay.assertHealthy();
            }
        }
    }

    @Test(timeout = 10000)
    public void silentPeerFailsQueuedWriteAtHandshakeTimeoutAndClosesChannel() throws Exception {
        // 静默对端持续接收握手数据但从不回复，客户端使用短握手总超时。
        String observation = "client-silent-" + SocketTestSupport.availableUdpPort();
        ConnectorConfig clientConfig = this.handshakeClientConfig(false, observation, 100, 800);
        UdpDtlsObservationPipeline.reset(observation);

        try (SilentUdpPeer peer = SilentUdpPeer.start(); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            client.init();
            InterAddress target = new InterAddress("testprotocol", "127.0.0.1", peer.port(), "default");
            MetChannel outgoing = client.connect(target).get(3, TimeUnit.SECONDS);

            // 握手期间提交的业务报文必须在总超时到达时失败，而不能永久留在写队列。
            Future<MetChannel> writing = outgoing.sendData(request(22, "silent-peer"));
            assertTrue(peer.awaitDatagram(1, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                writing.get(2, TimeUnit.SECONDS);
            });

            // 超时失败同时关闭底层通道，观察器应收到唯一的关闭生命周期通知。
            assertTrue(failure.getCause() instanceof TimeoutException);
            assertEquals("DTLS handshake timed out", failure.getCause().getMessage());
            assertEquals(Boolean.TRUE, UdpDtlsObservationPipeline.awaitClose(observation));
            peer.assertHealthy();
        }
    }

    private void verifyRoundTrip(boolean mutualAuthentication) throws Exception {
        // 通过 MetConfiguration 配置安全 UDP 端点，观察器位于生产 DTLS 管线之后。
        int port = SocketTestSupport.availableUdpPort();
        String serverObservation = "server-" + port;
        String clientObservation = "client-" + port;
        ConnectorConfig serverConfig = this.serverConfig(port, mutualAuthentication, serverObservation);
        ConnectorConfig clientConfig = this.clientConfig(mutualAuthentication, clientObservation);
        UdpDtlsObservationPipeline.reset(serverObservation);
        UdpDtlsObservationPipeline.reset(clientObservation);

        try (ConnectorManager server = SocketTestSupport.manager(serverConfig); //
             ConnectorManager client = SocketTestSupport.manager(clientConfig)) {
            server.init();
            client.init();
            Inbox serverInbox = new Inbox();
            Inbox clientInbox = new Inbox();
            server.subscribe(serverInbox);
            client.subscribe(clientInbox);

            // 启动监听并由客户端创建 DTLS peer，配置经过 ConnectorSettings 和 TransportConfig 进入 Neta。
            server.startInbound();
            InterAddress address = server.getListenAddress("testprotocol");
            MetChannel outgoing = client.connect(address).get(3, TimeUnit.SECONDS);
            assertEquals("DTLSv1.2", UdpDtlsObservationPipeline.await(serverObservation));
            assertEquals("DTLSv1.2", UdpDtlsObservationPipeline.await(clientObservation));
            String requestValue = "x".repeat(1000);
            outgoing.sendData(request(1, requestValue)).get(3, TimeUnit.SECONDS);
            Delivery accepted = serverInbox.take();
            RequestPayload request = (RequestPayload) accepted.payload;
            assertEquals(requestValue, request.getTargetMethod());

            // 双向消息在同一安全 peer 上完成，实际握手协议必须是 DTLS 1.2。
            accepted.channel.sendData(response(1, "secure-response")).get(3, TimeUnit.SECONDS);
            Delivery returned = clientInbox.take();
            ResponsePayload response = (ResponsePayload) returned.payload;
            assertSame(outgoing, returned.channel);
            assertEquals("secure-response", response.getReturnData());
        }
    }

    private ConnectorConfig serverConfig(int port, boolean mutualAuthentication, String observation) {
        Map<String, String> options = this.endpointOptions(observation);
        options.put("tlsKeyStore", SERVER_STORE);
        options.put("tlsKeyStorePassword", PASSWORD);
        if (mutualAuthentication) {
            options.put("tlsClientAuth", "REQUIRE");
            options.put("tlsTrustStore", TRUST_STORE);
            options.put("tlsTrustStorePassword", PASSWORD);
        }
        return this.endpoint(port, true, options);
    }

    private ConnectorConfig clientConfig(boolean mutualAuthentication, String observation) {
        return this.endpoint(0, false, this.clientOptions(mutualAuthentication, observation));
    }

    private ConnectorConfig handshakeClientConfig(boolean mutualAuthentication, String observation, //
            int retryTimeout, int handshakeTimeout) {
        Map<String, String> options = this.clientOptions(mutualAuthentication, observation);
        options.put("dtlsRetransmitTimeout", Integer.toString(retryTimeout));
        options.put("dtlsHandshakeTimeout", Integer.toString(handshakeTimeout));
        return this.endpoint(0, false, options);
    }

    private Map<String, String> clientOptions(boolean mutualAuthentication, String observation) {
        Map<String, String> options = this.endpointOptions(observation);
        options.put("tlsTrustStore", TRUST_STORE);
        options.put("tlsTrustStorePassword", PASSWORD);
        if (mutualAuthentication) {
            options.put("tlsKeyStore", CLIENT_STORE);
            options.put("tlsKeyStorePassword", PASSWORD);
        }
        return options;
    }

    private Map<String, String> endpointOptions(String observation) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("workerThread", "1");
        options.put("connectTimeout", "3000");
        options.put("tlsEnabled", "true");
        options.put("transportPipeline", UdpDtlsObservationPipeline.class.getName());
        options.put("dtlsObservation", observation);
        return options;
    }

    private ConnectorConfig endpoint(int port, boolean bindEnabled, Map<String, String> options) {
        InterAddress address = InterAddress.forBinding("udp", "127.0.0.1", port, "default");
        ProtocolConfig protocol = ProtocolConfig.newConfig("testprotocol", null, Map.of());
        return new ConnectorConfig("udp-0", address, options, List.of(protocol), bindEnabled);
    }

    private static final class Delivery {
        private final MetChannel channel;
        private final Payload    payload;

        private Delivery(MetChannel channel, Payload payload) {
            this.channel = channel;
            this.payload = payload;
        }
    }

    private static final class Inbox implements ConnectorSubscriber {
        private final BlockingQueue<Delivery> messages = new LinkedBlockingQueue<>();

        @Override
        public void onMessage(MetChannel channel, long requestId, Payload payload) {
            this.messages.add(new Delivery(channel, payload));
        }

        private Delivery take() throws InterruptedException, TimeoutException {
            Delivery delivery = this.messages.poll(3, TimeUnit.SECONDS);
            if (delivery == null) {
                throw new TimeoutException("Expected a decoded DTLS datagram");
            }
            return delivery;
        }
    }
}
