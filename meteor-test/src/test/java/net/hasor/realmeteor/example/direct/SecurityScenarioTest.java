/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.direct.SecurityExample;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.example.order.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.example.order.Order;
import org.junit.Test;
import static org.junit.Assert.*;

/** 安全配置复用普通多协议例子，真实调用验证 TLS 身份认证与 HTTP 版本协商。 */
public class SecurityScenarioTest {
    private static final String SERVER_STORE = "classpath:/tls/multiprotocol/server.p12";
    private static final String CLIENT_STORE = "classpath:/tls/multiprotocol/client.p12";
    private static final String TRUST_STORE  = "classpath:/tls/multiprotocol/trust.p12";
    private static final String PASSWORD     = "meteor-test";

    @Test(timeout = 30000)
    public void rsfAndDubboShareAServerAuthenticatedTlsListener() throws Exception {
        // 复用普通端点和协议挂载，只在物理传输上增加服务端证书和客户端信任库。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        SecurityExample.server(incoming, SERVER_STORE, PASSWORD, null, null, false);
        MetConfiguration outgoing = DirectRpcExample.client();
        SecurityExample.client(outgoing, TRUST_STORE, PASSWORD, null, null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client);

            // RSF 写入、Dubbo 读取；两种报文在同一个 TLS 端口上访问同一实现。
            OrderDemoService rsf = client.getRsfClient(server.getListenAddress("rsf")).getRemote(binding);
            rsf.save(this.order(501, "server authenticated socket"));
            OrderDemoService dubbo = client.getRsfClient(server.getListenAddress("dubbo")).getRemote(binding);
            assertEquals("server authenticated socket", dubbo.find(this.query(501)).getDescription());
            assertEquals(server.getListenAddress("rsf").getPort(), server.getListenAddress("dubbo").getPort());
        }
    }

    @Test(timeout = 30000)
    public void https1SelectsHttp11AndServesSharedOrders() throws Exception {
        // HTTPS 监听同时接受两种 HTTP 版本，JSON-RPC 与 SOAP 的客户端选择 HTTP/1.1。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        SecurityExample.server(incoming, SERVER_STORE, PASSWORD, null, null, false);
        MetConfiguration outgoing = DirectRpcExample.client();
        SecurityExample.client(outgoing, TRUST_STORE, PASSWORD, null, null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client);

            // 独立握手确认 ALPN 的实际结果，再通过真实 HTTP/1.1 代理访问业务。
            int port = server.getListenAddress("jsonrpc").getPort();
            assertEquals("http/1.1", this.negotiatedProtocol(port, "http/1.1"));
            OrderDemoService json = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(binding);
            json.save(this.order(502, "HTTPS over HTTP/1.1"));
            OrderDemoService soap = client.getRsfClient(server.getListenAddress("soap")).getRemote(binding);
            assertEquals("HTTPS over HTTP/1.1", soap.find(this.query(502)).getDescription());
        }
    }

    @Test(timeout = 30000)
    public void https2SelectsH2AndServesSharedOrders() throws Exception {
        // gRPC 与 Triple 复用普通业务绑定和 Proto，安全配置属于 HTTP 端点。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        SecurityExample.server(incoming, SERVER_STORE, PASSWORD, null, null, false);
        MetConfiguration outgoing = DirectRpcExample.client();
        SecurityExample.client(outgoing, TRUST_STORE, PASSWORD, null, null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client);

            // 真实协商得到 h2，两个协议随后在同一 HTTP/2 端口读取共享订单。
            int port = server.getListenAddress("grpc").getPort();
            assertEquals("h2", this.negotiatedProtocol(port, "h2"));
            OrderDemoService grpc = client.getRsfClient(server.getListenAddress("grpc")).getRemote(binding);
            grpc.save(this.order(503, "HTTPS over HTTP/2"));
            OrderDemoService triple = client.getRsfClient(server.getListenAddress("triple")).getRemote(binding);
            assertEquals("HTTPS over HTTP/2", triple.find(this.query(503)).getDescription());
        }
    }

    @Test(timeout = 30000)
    public void clientIdentityAuthenticatesBothSocketAndHttpTransports() throws Exception {
        // 服务端统一要求客户端证书，出站配置在 TCP 与 HTTP 上提供同一个客户端身份。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        SecurityExample.server(incoming, SERVER_STORE, PASSWORD, TRUST_STORE, PASSWORD, true);
        MetConfiguration outgoing = DirectRpcExample.client();
        SecurityExample.client(outgoing, TRUST_STORE, PASSWORD, CLIENT_STORE, PASSWORD);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client);

            // 双向认证后的 HTTP/1.1、TCP 和 HTTP/2 调用都落到同一份订单状态。
            OrderDemoService json = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(binding);
            json.save(this.order(504, "mutual TLS"));
            OrderDemoService rsf = client.getRsfClient(server.getListenAddress("rsf")).getRemote(binding);
            assertEquals("mutual TLS", rsf.find(this.query(504)).getDescription());
            OrderDemoService dubbo = client.getRsfClient(server.getListenAddress("dubbo")).getRemote(binding);
            assertEquals("mutual TLS", dubbo.find(this.query(504)).getDescription());
            OrderDemoService grpc = client.getRsfClient(server.getListenAddress("grpc")).getRemote(binding);
            assertEquals("mutual TLS", grpc.find(this.query(504)).getDescription());
        }
    }

    @Test(timeout = 30000)
    public void missingClientIdentityFailsWithoutWaitingForRpcTimeout() throws Exception {
        // 服务端要求双向认证，这个客户端仅配置服务端信任库，故意不提供自己的身份。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        SecurityExample.server(incoming, SERVER_STORE, PASSWORD, TRUST_STORE, PASSWORD, true);
        MetConfiguration outgoing = DirectRpcExample.client();
        SecurityExample.client(outgoing, TRUST_STORE, PASSWORD, null, null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            OrderDemoServiceImpl implementation = new OrderDemoServiceImpl();
            DirectRpcExample.publish(server, implementation);
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client);

            // 调用必须及时得到认证失败，不能把等待 RPC 超时当作身份校验成功。
            Class<?>[] parameterTypes = { Order.class };
            Object[] arguments = { this.order(505, "must be rejected") };
            MetFuture pending = client.getRsfClient(server.getListenAddress("rsf")) //
                    .asyncInvoke(binding, "save", parameterTypes, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(5, TimeUnit.SECONDS);
            });
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertThrows(IllegalArgumentException.class, () -> {
                implementation.find(this.query(505));
            });
        }
    }

    private String negotiatedProtocol(int port, String protocol) throws Exception {
        // 直接使用 JDK TLS 客户端观察端点实际协商结果，证书校验保持启用。
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = this.getClass().getResourceAsStream("/tls/multiprotocol/trust.p12")) {
            trustStore.load(input, PASSWORD.toCharArray());
        }
        trust.init(trustStore);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);

        // 只声明本场景的目标版本；服务端选择结果必须与后续协议调用一致。
        try (SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setApplicationProtocols(new String[] { protocol });
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            return socket.getApplicationProtocol();
        }
    }

    private Order order(int id, String description) {
        Order order = new Order();
        order.setId(id);
        order.setDescription(description);
        order.setAttachment(new byte[] { 1, 2, 3 });
        return order;
    }

    private OrderQuery query(int id) {
        OrderQuery query = new OrderQuery();
        query.setId(id);
        return query;
    }
}
