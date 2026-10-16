/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.scenario;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.scenario.example.Echo;
import net.hasor.meteor.protocol.grpc.scenario.example.EchoImpl;
import net.hasor.meteor.protocol.grpc.scenario.example.Message;
import net.hasor.meteor.protocol.grpc.scenario.support.GrpcHttp2Peer;
import net.hasor.meteor.protocol.grpc.scenario.support.GrpcTlsPeer;
import org.junit.Test;
import static org.junit.Assert.*;

/** 官方 gRPC-Java 与 Meteor 经真实 TLS 互通，并区分 ALPN、h2c 与 HTTP/1 路径。 */
public class GrpcSecureInteropTest {
    @Test(timeout = 25000)
    public void officialClientInvokesMeteorProtobufOverTls() throws Exception {
        // 服务端仅要求单向 TLS，官方客户端独立校验本地 CA 签发的服务端证书。
        MetConfiguration configuration = this.serverConfiguration(false, "h2");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 官方通道协商 h2，以 gzip 压缩 Protobuf 请求并独立解码服务器的 gzip 响应。
            int port = server.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .overrideAuthority("localhost") //
                    .sslContext(GrpcTlsPeer.clientSsl(null)) //
                    .build();
            try {
                CallOptions deadline = CallOptions.DEFAULT //
                        .withDeadlineAfter(5, TimeUnit.SECONDS) //
                        .withCompression("gzip");
                Message request = Message.of("official TLS 中文", 4);
                Message result = ClientCalls.blockingUnaryCall(peer, GrpcTlsPeer.method(), deadline, request);
                assertEquals("official TLS 中文", result.getValue());
                assertEquals(5, result.getCount());
                assertEquals(1, implementation.getCalls());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 25000)
    public void meteorInvokesOfficialProtobufServerOverTls() throws Exception {
        // 官方服务使用独立 TLS 配置和业务实现，Meteor 客户端不提供客户端证书。
        AtomicInteger calls = new AtomicInteger();
        MetConfiguration configuration = this.clientConfiguration(null);
        Server peer = NettyServerBuilder.forPort(0) //
                .sslContext(GrpcTlsPeer.serverSsl(false)) //
                .addService(GrpcTlsPeer.service(calls)) //
                .build().start();
        try (MetContext client = configuration.buildContext()) {
            client.start();
            MetBindInfo<Echo> info = client.publisher().rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 从公开代理入口发送 gzip 请求，官方服务解码后再以 gzip 返回业务消息。
            InterAddress address = new InterAddress("https", "127.0.0.1", peer.getPort(), "default");
            Echo remote = client.getRsfClient(address).getRemote(info);
            Message result = remote.echo(Message.of("Meteor TLS 中文", 4));
            assertEquals("Meteor TLS 中文", result.getValue());
            assertEquals(9, result.getCount());
            assertEquals(1, calls.get());
        } finally {
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 25000)
    public void officialClientInvokesMeteorProtobufOverMutualTls() throws Exception {
        // Meteor 要求客户端证书，官方客户端使用独立签发的 client 身份。
        MetConfiguration configuration = this.serverConfiguration(true, "h2");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 双方证书验证通过后完成一次普通 Protobuf RPC。
            int port = server.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .overrideAuthority("localhost") //
                    .sslContext(GrpcTlsPeer.clientSsl("client")) //
                    .build();
            try {
                CallOptions deadline = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                Message request = Message.of("official mTLS", 6);
                Message result = ClientCalls.blockingUnaryCall(peer, GrpcTlsPeer.method(), deadline, request);
                assertEquals("official mTLS", result.getValue());
                assertEquals(7, result.getCount());
                assertEquals(1, implementation.getCalls());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 25000)
    public void meteorInvokesOfficialProtobufServerOverMutualTls() throws Exception {
        // 官方服务要求客户端证书，Meteor 通过公开 connector 配置选择 client 身份。
        AtomicInteger calls = new AtomicInteger();
        MetConfiguration configuration = this.clientConfiguration("client");
        Server peer = NettyServerBuilder.forPort(0) //
                .sslContext(GrpcTlsPeer.serverSsl(true)) //
                .addService(GrpcTlsPeer.service(calls)) //
                .build().start();
        try (MetContext client = configuration.buildContext()) {
            client.start();
            MetBindInfo<Echo> info = client.publisher().rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 真实网络调用成功才证明独立客户端身份被官方服务接受。
            InterAddress address = new InterAddress("https", "127.0.0.1", peer.getPort(), "default");
            Echo remote = client.getRsfClient(address).getRemote(info);
            Message result = remote.echo(Message.of("Meteor mTLS", 6));
            assertEquals("Meteor mTLS", result.getValue());
            assertEquals(11, result.getCount());
            assertEquals(1, calls.get());
        } finally {
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 25000)
    public void officialClientWithoutCertificateCannotInvokeMutualTlsMeteor() throws Exception {
        // 服务端要求 mTLS，而官方客户端只加载 CA 信任，不配置任何客户端身份。
        MetConfiguration configuration = this.serverConfiguration(true, "h2");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 握手被拒绝必须以连接不可用返回，不能靠业务调用超时冒充认证失败。
            int port = server.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .overrideAuthority("localhost") //
                    .sslContext(GrpcTlsPeer.clientSsl(null)) //
                    .build();
            try {
                CallOptions deadline = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                Message request = Message.of("missing client identity", 1);
                StatusRuntimeException error = assertThrows(StatusRuntimeException.class, () -> {
                    ClientCalls.blockingUnaryCall(peer, GrpcTlsPeer.method(), deadline, request);
                });
                assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode());
                assertEquals(0, implementation.getCalls());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 25000)
    public void meteorWithoutCertificateCannotInvokeMutualTlsOfficialServer() throws Exception {
        // 官方服务明确要求客户端证书，Meteor 客户端保持无身份的单向 TLS 配置。
        AtomicInteger calls = new AtomicInteger();
        MetConfiguration configuration = this.clientConfiguration(null);
        Server peer = NettyServerBuilder.forPort(0) //
                .sslContext(GrpcTlsPeer.serverSsl(true)) //
                .addService(GrpcTlsPeer.service(calls)) //
                .build().start();
        try (MetContext client = configuration.buildContext()) {
            client.start();
            MetBindInfo<Echo> info = client.publisher().rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 调用方应感知网络认证失败，业务实现不应执行，也不接受仅等待超时。
            InterAddress address = new InterAddress("https", "127.0.0.1", peer.getPort(), "default");
            Message request = Message.of("missing Meteor identity", 1);
            var remote = client.getRsfClient(address);
            var pending = remote.asyncInvoke(info, "echo", new Class<?>[] { Message.class }, new Object[] { request });
            ExecutionException error = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertTrue(error.toString(), error.getCause() instanceof IOException);
            assertEquals(0, calls.get());
        } finally {
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 25000)
    public void tlsHttp11CannotInvokeGrpcService() throws Exception {
        // 监听允许协商 HTTP/1.1，但挂载的 gRPC 业务仍只接受 HTTP/2。
        MetConfiguration configuration = this.serverConfiguration(false, "h2,http/1.1");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // JDK TLS 对端显式选择 HTTP/1.1，读取真实 ALPN 结果后提交合法 gRPC 正文。
            int port = server.bindAddress("grpc").getPort();
            var sockets = GrpcTlsPeer.jdkClientContext().getSocketFactory();
            try (SSLSocket peer = (SSLSocket) sockets.createSocket("localhost", port)) {
                peer.setSoTimeout(5000);
                SSLParameters parameters = peer.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setApplicationProtocols(new String[] { "http/1.1" });
                peer.setSSLParameters(parameters);
                peer.startHandshake();
                assertEquals("http/1.1", peer.getApplicationProtocol());

                byte[] body = GrpcTlsPeer.grpcBody(Message.of("wrong HTTP version", 1));
                String headers = "POST /example.Echo/echo HTTP/1.1\r\nHost: localhost\r\n" //
                        + "Content-Type: application/grpc+proto\r\nTE: trailers\r\n" //
                        + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                peer.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                peer.getOutputStream().write(body);
                peer.getOutputStream().flush();

                // 明确返回版本不支持，不能执行业务后再丢弃响应。
                var input = new InputStreamReader(peer.getInputStream(), StandardCharsets.US_ASCII);
                String status = new BufferedReader(input).readLine();
                assertNotNull(status);
                assertTrue(status, status.startsWith("HTTP/1.1 505 "));
                assertEquals(0, implementation.getCalls());
            }
        }
    }

    @Test(timeout = 25000)
    public void officialGrpcClientRejectsHttp11OnlyAlpn() throws Exception {
        // 服务端仅提供 http/1.1，官方 gRPC 客户端要求 h2，双方没有共同 ALPN。
        MetConfiguration configuration = this.serverConfiguration(false, "http/1.1");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 缺少 h2 协商结果必须拒绝建立可用通道，不允许降级执行 gRPC。
            int port = server.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .overrideAuthority("localhost") //
                    .sslContext(GrpcTlsPeer.clientSsl(null)) //
                    .build();
            try {
                CallOptions deadline = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                Message request = Message.of("ALPN mismatch", 1);
                StatusRuntimeException error = assertThrows(StatusRuntimeException.class, () -> {
                    ClientCalls.blockingUnaryCall(peer, GrpcTlsPeer.method(), deadline, request);
                });
                assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode());
                assertEquals(0, implementation.getCalls());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 25000)
    public void officialHttp2PeerObservesAlpnAndGrpcTrailersOverTls() throws Exception {
        // 官方 Netty 对端保留完整 HTTP/2 帧，分别检查 ALPN、初始头和最终 trailers。
        MetConfiguration configuration = this.serverConfiguration(false, "h2,http/1.1");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 同一真实 TLS 连接协商 h2，并收取单次调用的数据及终止状态。
            int port = server.bindAddress("grpc").getPort();
            try (GrpcHttp2Peer peer = GrpcHttp2Peer.tls(port)) {
                assertEquals("h2", peer.applicationProtocol());
                peer.send(GrpcTlsPeer.grpcBody(Message.of("Netty ALPN h2", 2)));
                peer.awaitResponse();
                assertEquals("200", peer.status());
                assertEquals("0", peer.grpcStatus());
                assertTrue(peer.endedWithTrailers());
                assertEquals(1, peer.streamId());
                Message result = GrpcTlsPeer.readGrpcBody(peer.body());
                assertEquals("Netty ALPN h2", result.getValue());
                assertEquals(3, result.getCount());
                assertEquals(1, implementation.getCalls());
            }
        }
    }

    @Test(timeout = 25000)
    public void officialH2cUpgradeCarriesGrpcRequestAndFinalTrailers() throws Exception {
        // 官方 Netty 升级客户端从 HTTP/1.1 切换至 h2c，业务服务通过公开入口发布。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // Upgrade 请求自身承载正文；stream 1 返回初始头、业务数据和成功 trailers。
            int port = server.bindAddress("grpc").getPort();
            try (GrpcHttp2Peer peer = GrpcHttp2Peer.h2cUpgrade(port)) {
                peer.send(GrpcTlsPeer.grpcBody(Message.of("Netty h2c upgrade", 3)));
                assertTrue(peer.wasUpgraded());
                peer.awaitResponse();
                assertEquals("200", peer.status());
                assertEquals("0", peer.grpcStatus());
                assertTrue(peer.endedWithTrailers());
                assertEquals(1, peer.streamId());
                Message result = GrpcTlsPeer.readGrpcBody(peer.body());
                assertEquals("Netty h2c upgrade", result.getValue());
                assertEquals(4, result.getCount());
                assertEquals(1, implementation.getCalls());
            }
        }
    }

    private MetConfiguration serverConfiguration(boolean clientAuth, String alpn) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsClientAuth", clientAuth ? "REQUIRE" : "NONE") //
                .option("tlsAlpn", alpn) //
                .option("httpScheme", "https") //
                .protocol("grpc").addressScheme("https").option("compression", "gzip");
        return configuration;
    }

    private MetConfiguration clientConfiguration(String identity) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.setDefaultTimeout(5000);
        var connector = configuration.connector("http") //
                .option("tlsEnabled", true) //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsAlpn", "h2") //
                .option("httpScheme", "https") //
                .option("httpVersion", 2);
        if (identity != null) {
            connector.option("tlsKeyStore", "classpath:/tls/acceptance/" + identity + ".p12");
            connector.option("tlsKeyStorePassword", "meteor-test");
        }
        connector.protocol("grpc").addressScheme("https").option("compression", "gzip");
        return configuration;
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
    }
}
