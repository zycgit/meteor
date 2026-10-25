/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.soap;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import jakarta.xml.ws.Endpoint;
import jakarta.xml.ws.soap.MTOMFeature;
import jakarta.xml.ws.soap.SOAPBinding;
import net.hasor.example.protocol.soap.MetroBinaryServiceImpl;
import net.hasor.example.protocol.soap.MetroEchoProvider;
import net.hasor.example.protocol.soap.MetroRpcEchoServiceImpl;
import net.hasor.example.protocol.soap.MetroSoap12Provider;

/** 独立 Metro 进程拥有 HTTP(S) 监听及 Endpoint。 */
final class MetroPeerServer implements AutoCloseable {
    private final HttpServer server;
    private final Endpoint   endpoint;

    MetroPeerServer(String profile, String version, String tlsDirectory) throws Exception {
        // 配置只描述现有契约，不通过反射任意构造服务对象。
        String binding = switch (version) {
            case "1.1" -> SOAPBinding.SOAP11HTTP_BINDING;
            case "1.2" -> SOAPBinding.SOAP12HTTP_BINDING;
            default -> throw new IllegalArgumentException("Unknown SOAP version: " + version);
        };
        String path;
        switch (profile) {
            case "echo": {
                Object implementation = switch (version) {
                    case "1.1" -> new MetroEchoProvider();
                    default -> new MetroSoap12Provider();
                };
                this.endpoint = Endpoint.create(binding, implementation);
                path = "/services/EchoService";
                break;
            }
            case "rpc": {
                this.endpoint = Endpoint.create(binding, new MetroRpcEchoServiceImpl());
                path = "/services/RpcEchoService";
                break;
            }
            case "mtom": {
                MTOMFeature mtom = new MTOMFeature(true, 0);
                this.endpoint = Endpoint.create(binding, new MetroBinaryServiceImpl(), mtom);
                path = "/services/BinaryService";
                break;
            }
            default: {
                throw new IllegalArgumentException("Unknown Metro server profile: " + profile);
            }
        }
        this.server = createServer(tlsDirectory);
        try {
            this.endpoint.publish(this.server.createContext(path));
            this.server.start();
        } catch (Throwable failure) {
            this.close();
            throw failure;
        }
    }

    int getPort() {
        return this.server.getAddress().getPort();
    }

    private static HttpServer createServer(String tlsDirectory) throws Exception {
        InetSocketAddress address = new InetSocketAddress("0.0.0.0", 0);
        if (tlsDirectory.isEmpty()) {
            return HttpServer.create(address, 0);
        }
        SSLContext context = SoapPeerFiles.sslContext(Path.of(tlsDirectory), "server");
        HttpsServer server = HttpsServer.create(address, 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters parameters) {
                // 原有双向认证场景继续要求客户端证书。
                SSLParameters ssl = this.getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                parameters.setSSLParameters(ssl);
            }
        });
        return server;
    }

    @Override
    public void close() {
        try {
            this.endpoint.stop();
        } finally {
            this.server.stop(0);
        }
    }
}
