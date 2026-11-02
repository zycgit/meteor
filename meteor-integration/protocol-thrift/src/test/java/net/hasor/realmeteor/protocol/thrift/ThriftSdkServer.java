/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.layered.TFramedTransport;

/** 同进程 SDK 测试服务，仅持有实际监听端口和属于自身的资源。 */
final class ThriftSdkServer implements AutoCloseable {
    private final int           port;
    private final AutoCloseable resources;

    private ThriftSdkServer(int port, AutoCloseable resources) {
        this.port = port;
        this.resources = resources;
    }

    static ThriftSdkServer tcp() throws Exception {
        // 官方 TCP 服务持有监听器和处理线程，等待 SDK 明确进入 serving 状态。
        TServerSocket listener = new TServerSocket(new InetSocketAddress("127.0.0.1", 0));
        TThreadPoolServer.Args options = new TThreadPoolServer.Args(listener) //
                .processor(processor()).protocolFactory(new TBinaryProtocol.Factory()) //
                .transportFactory(new TFramedTransport.Factory()) //
                .minWorkerThreads(1).maxWorkerThreads(4).stopTimeoutVal(2);
        TThreadPoolServer server = new TThreadPoolServer(options);
        Thread serving = new Thread(server::serve, "thrift-sdk-basic-server");
        serving.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!server.isServing()) {
                if (!serving.isAlive() || System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Official Thrift server did not become ready");
                }
                TimeUnit.MILLISECONDS.sleep(10);
            }
            return new ThriftSdkServer(listener.getServerSocket().getLocalPort(), //
                    () -> closeTcp(server, listener, serving));
        } catch (Exception failure) {
            try {
                closeTcp(server, listener, serving);
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    static ThriftSdkServer http() throws IOException {
        // JDK HTTP 只承载官方 Processor 的正文，不自行实现 Thrift 线协议。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            TProcessor processor = processor();
            server.setExecutor(executor);
            server.createContext("/thrift", exchange -> processHttp(processor, exchange));
            server.start();
            return new ThriftSdkServer(server.getAddress().getPort(), () -> {
                server.stop(0);
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Official Thrift HTTP workers did not stop");
                }
            });
        } catch (RuntimeException failure) {
            server.stop(0);
            executor.shutdownNow();
            throw failure;
        }
    }

    private static TProcessor processor() {
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public String echo(String value) {
                return "thrift-sdk:" + value;
            }
        };
        return new ExtendedInteropService.Processor<>(implementation);
    }

    private static void closeTcp(TThreadPoolServer server, TServerSocket listener, Thread serving) throws Exception {
        server.stop();
        listener.close();
        serving.join(5000);
        if (serving.isAlive()) {
            throw new IllegalStateException("Official Thrift server did not stop");
        }
    }

    private static void processHttp(TProcessor processor, HttpExchange exchange) throws IOException {
        try (exchange) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (TIOStreamTransport transport = new TIOStreamTransport(exchange.getRequestBody(), output)) {
                TBinaryProtocol protocol = new TBinaryProtocol(transport);
                processor.process(protocol, protocol);
                transport.flush();
            } catch (TException failure) {
                throw new IOException("Official Thrift HTTP processor failed", failure);
            }
            byte[] response = output.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/x-thrift");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    int getPort() {
        return this.port;
    }

    @Override
    public void close() throws Exception {
        this.resources.close();
    }
}
