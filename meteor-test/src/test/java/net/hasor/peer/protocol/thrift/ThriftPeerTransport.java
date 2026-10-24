/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.thrift;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.net.ssl.*;
import com.sun.net.httpserver.*;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.*;
import org.apache.thrift.server.ServerContext;
import org.apache.thrift.server.TServerEventHandler;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.*;
import org.apache.thrift.transport.layered.TFramedTransport;

/** 独立官方对端的网络资源；Thrift 报文完全交给 Apache SDK 处理。 */
final class ThriftPeerTransport {
    private ThriftPeerTransport() {
    }

    static TProtocolFactory protocolFactory(Map<String, String> arguments) {
        return switch (arguments.getOrDefault("protocol", "binary")) {
            case "binary" -> new TBinaryProtocol.Factory();
            case "compact" -> new TCompactProtocol.Factory();
            case "json" -> new TJSONProtocol.Factory();
            default -> throw new IllegalArgumentException("Unknown protocol: " + arguments.get("protocol"));
        };
    }

    static TTransport client(Map<String, String> arguments) throws Exception {
        String transport = arguments.getOrDefault("transport", "tcp");
        String host = arguments.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(arguments.getOrDefault("port", "0"));
        boolean secureHttp = switch (transport) {
            case "https" -> true;
            default -> false;
        };

        // HTTP 消息的边界由 HTTP 自身提供，不增加 TCP 的 frame 前缀。
        switch (transport) {
            case "http":
            case "https":
                if (secureHttp) {
                    SSLContext context = sslContext(arguments);
                    HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
                }
                String path = arguments.getOrDefault("path", "/thrift");
                String address = new URI(transport, null, host, port, path, null, null).toString();
                THttpClient http = new THttpClient(address);
                http.setConnectTimeout(5000);
                http.setReadTimeout(5000);
                http.open();
                return http;
            case "tcp":
            case "tls":
                break;
            default:
                throw new IllegalArgumentException("Unknown transport: " + transport);
        }

        // TLS 在返回前完成证书和主机身份验证，返回的 transport 由调用方独占关闭。
        TTransport socket = switch (transport) {
            case "tls" -> tlsClient(arguments, host, port);
            default -> new TSocket(host, port, 5000);
        };
        try {
            TTransport opened = socket;
            if (Boolean.parseBoolean(arguments.getOrDefault("framed", "true"))) {
                opened = new TFramedTransport(socket);
            }
            if (!opened.isOpen()) {
                opened.open();
            }
            return opened;
        } catch (Exception error) {
            socket.close();
            throw error;
        }
    }

    static void server(Map<String, String> arguments, TProcessor processor) throws Exception {
        switch (arguments.getOrDefault("transport", "tcp")) {
            case "tcp":
            case "tls":
                socketServer(arguments, processor);
                break;
            case "http":
            case "https":
                httpServer(arguments, processor);
                break;
            default:
                throw new IllegalArgumentException("Unknown transport: " + arguments.get("transport"));
        }
    }

    private static TSocket tlsClient(Map<String, String> arguments, String host, int port) throws Exception {
        SSLContext context = sslContext(arguments);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        try {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.startHandshake();
            return new TSocket(socket);
        } catch (Exception error) {
            socket.close();
            throw error;
        }
    }

    private static void socketServer(Map<String, String> arguments, TProcessor processor) throws Exception {
        // 官方线程池处理请求；当前方法持有监听器、已接入连接和服务线程的关闭责任。
        Set<TTransport> clients = ConcurrentHashMap.newKeySet();
        AtomicBoolean closing = new AtomicBoolean();
        try (TServerSocket listener = serverSocket(arguments)) {
            int port = listener.getServerSocket().getLocalPort();
            TTransportFactory framing = new TTransportFactory();
            if (Boolean.parseBoolean(arguments.getOrDefault("framed", "true"))) {
                framing = new TFramedTransport.Factory();
            }
            TThreadPoolServer.Args options = new TThreadPoolServer.Args(listener) //
                    .processor(processor) //
                    .protocolFactory(protocolFactory(arguments)) //
                    .transportFactory(framing) //
                    .minWorkerThreads(1) //
                    .maxWorkerThreads(32) //
                    .stopTimeoutVal(3) //
                    .stopTimeoutUnit(TimeUnit.SECONDS);
            TThreadPoolServer server = new TThreadPoolServer(options);
            server.setServerEventHandler(connectionEvents(clients, closing));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread serving = new Thread(() -> {
                try {
                    server.serve();
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "thrift-official-peer");
            serving.setDaemon(true);
            serving.start();
            try {
                // isServing 在官方监听准备完成后变为 true，失败或超时不能输出 READY。
                awaitServing(server, serving, failure);
                awaitInput(port);
            } finally {
                closing.set(true);
                server.stop();
                clients.forEach(TTransport::close);
                serving.join(5000);
                if (serving.isAlive()) {
                    serving.interrupt();
                    throw new IllegalStateException("Official Thrift server did not stop within 5 seconds");
                }
            }
        }
    }

    private static TServerEventHandler connectionEvents(Set<TTransport> clients, AtomicBoolean closing) {
        ServerContext connection = new ServerContext() {
            @Override
            public <T> T unwrap(Class<T> type) {
                throw new IllegalArgumentException("No application connection context: " + type.getName());
            }

            @Override
            public boolean isWrapperFor(Class<?> type) {
                return false;
            }
        };
        return new TServerEventHandler() {
            @Override
            public void preServe() {
                if (closing.get()) {
                    throw new IllegalStateException("Peer closed before server startup");
                }
            }

            @Override
            public ServerContext createContext(TProtocol input, TProtocol output) {
                TTransport transport = input.getTransport();
                clients.add(transport);
                if (closing.get()) {
                    transport.close();
                }
                return connection;
            }

            @Override
            public void deleteContext(ServerContext context, TProtocol input, TProtocol output) {
                clients.remove(input.getTransport());
            }

            @Override
            public void processContext(ServerContext context, TTransport input, TTransport output) {
                // 请求处理仍由官方线程池和 processor 负责。
            }
        };
    }

    private static TServerSocket serverSocket(Map<String, String> arguments) throws Exception {
        String host = arguments.getOrDefault("host", "0.0.0.0");
        int port = Integer.parseInt(arguments.getOrDefault("port", "0"));
        InetSocketAddress address = new InetSocketAddress(host, port);
        switch (arguments.getOrDefault("transport", "tcp")) {
            case "tcp":
                return new TServerSocket(address);
            case "tls":
                break;
            default:
                throw new IllegalArgumentException("Expected socket transport");
        }
        SSLContext context = sslContext(arguments);
        SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.setNeedClientAuth(Boolean.parseBoolean(arguments.getOrDefault("client-auth", "false")));
            socket.bind(address);
            return new TServerSocket(socket);
        } catch (Exception error) {
            socket.close();
            throw error;
        }
    }

    private static void awaitServing(TThreadPoolServer server, Thread serving, //
            AtomicReference<Throwable> failure) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!server.isServing()) {
            if (!serving.isAlive() || failure.get() != null) {
                throw new IllegalStateException("Official Thrift server failed to start", failure.get());
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Official Thrift server did not start within 5 seconds");
            }
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted while starting official Thrift server");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static void httpServer(Map<String, String> arguments, TProcessor processor) throws Exception {
        String host = arguments.getOrDefault("host", "0.0.0.0");
        int port = Integer.parseInt(arguments.getOrDefault("port", "0"));
        InetSocketAddress address = new InetSocketAddress(host, port);
        boolean secure = switch (arguments.getOrDefault("transport", "http")) {
            case "https" -> true;
            default -> false;
        };
        SSLContext context = secure ? sslContext(arguments) : null;
        HttpServer server = secure ? HttpsServer.create(address, 0) : HttpServer.create(address, 0);
        ExecutorService workers = Executors.newCachedThreadPool(task -> {
            Thread worker = new Thread(task, "thrift-official-http-peer");
            worker.setDaemon(true);
            return worker;
        });
        try {
            // HTTPS 只配置 JDK 的证书校验和可选客户端认证，不替代官方 Thrift 编解码。
            if (secure) {
                boolean clientAuth = Boolean.parseBoolean(arguments.getOrDefault("client-auth", "false"));
                ((HttpsServer) server).setHttpsConfigurator(new HttpsConfigurator(context) {
                    @Override
                    public void configure(HttpsParameters parameters) {
                        SSLParameters tls = getSSLContext().getDefaultSSLParameters();
                        tls.setNeedClientAuth(clientAuth);
                        parameters.setSSLParameters(tls);
                    }
                });
            }
            TProtocolFactory protocols = protocolFactory(arguments);
            String path = arguments.getOrDefault("path", "/thrift");
            server.createContext(path, exchange -> processHttp(exchange, processor, protocols));
            server.setExecutor(workers);
            server.start();
            awaitInput(server.getAddress().getPort());
        } finally {
            server.stop(0);
            workers.shutdownNow();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Official HTTP peer did not stop within 5 seconds");
            }
        }
    }

    private static void processHttp(HttpExchange exchange, TProcessor processor, //
            TProtocolFactory protocols) throws IOException {
        try (exchange) {
            // HTTP handler 只承载 POST 字节，报文读取、业务分派和响应写入都使用官方 API。
            switch (exchange.getRequestMethod()) {
                case "POST":
                    break;
                default:
                    exchange.getResponseHeaders().set("Allow", "POST");
                    exchange.sendResponseHeaders(405, -1);
                    return;
            }
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            try (TIOStreamTransport transport = new TIOStreamTransport(exchange.getRequestBody(), response)) {
                TProtocol input = protocols.getProtocol(transport);
                TProtocol output = protocols.getProtocol(transport);
                processor.process(input, output);
                transport.flush();
            } catch (Exception error) {
                error.printStackTrace(System.err);
                exchange.sendResponseHeaders(500, -1);
                return;
            }

            // oneway 也完成 200 响应，让官方 THttpClient.flush() 能完成本次 HTTP 交换。
            byte[] body = response.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/x-thrift");
            long responseLength = body.length == 0 ? -1 : body.length;
            exchange.sendResponseHeaders(200, responseLength);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        }
    }

    private static SSLContext sslContext(Map<String, String> arguments) throws Exception {
        char[] password = arguments.getOrDefault("password", "changeit").toCharArray();
        KeyManager[] keys = null;
        TrustManager[] trust = null;

        // 证书来源只限调用方提供的 PKCS12 文件；省略 trust-store 时保留 JDK 默认信任规则。
        String keyStorePath = arguments.get("key-store");
        if (keyStorePath != null) {
            KeyStore keyStore = loadStore(keyStorePath, password);
            KeyManagerFactory keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyFactory.init(keyStore, password);
            keys = keyFactory.getKeyManagers();
        }
        String trustStorePath = arguments.get("trust-store");
        if (trustStorePath != null) {
            KeyStore trustStore = loadStore(trustStorePath, password);
            String trustAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
            TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(trustAlgorithm);
            trustFactory.init(trustStore);
            trust = trustFactory.getTrustManagers();
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys, trust, null);
        return context;
    }

    private static KeyStore loadStore(String path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(Path.of(path))) {
            store.load(input, password);
        }
        return store;
    }

    private static void awaitInput(int port) throws IOException {
        // 父进程通过 stdin 的所有权控制服务器寿命，只有网络就绪后才发布端口。
        System.out.println("READY " + port);
        System.out.flush();
        while (System.in.read() != -1) {
            // EOF 之后由当前网络所有者关闭全部资源。
        }
    }
}
