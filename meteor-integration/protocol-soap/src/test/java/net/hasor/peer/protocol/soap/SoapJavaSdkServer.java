/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.soap;
import java.io.IOException;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import jakarta.xml.ws.Endpoint;
import jakarta.xml.ws.soap.SOAPBinding;

/** 测试持有的 Metro Endpoint，自己负责 SOAP 契约、分派和编解码。 */
public final class SoapJavaSdkServer implements AutoCloseable {
    private final HttpServer server;
    private final Endpoint   endpoint;

    private SoapJavaSdkServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Endpoint created = null;
        try {
            created = Endpoint.create(SOAPBinding.SOAP11HTTP_BINDING, new SoapJavaSdkService());
            created.publish(this.server.createContext("/services/EchoService"));
            this.server.start();
            this.endpoint = created;
        } catch (RuntimeException | Error failure) {
            try {
                if (created != null) {
                    created.stop();
                }
            } finally {
                this.server.stop(0);
            }
            throw failure;
        }
    }

    public static SoapJavaSdkServer start() throws IOException {
        return new SoapJavaSdkServer();
    }

    public int getPort() {
        return this.server.getAddress().getPort();
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
