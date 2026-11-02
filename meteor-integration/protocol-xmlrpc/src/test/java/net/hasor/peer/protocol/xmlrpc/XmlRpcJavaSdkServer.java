/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.xmlrpc;
import java.net.InetAddress;
import org.apache.xmlrpc.server.PropertyHandlerMapping;
import org.apache.xmlrpc.server.XmlRpcServerConfigImpl;
import org.apache.xmlrpc.webserver.WebServer;

/** 测试持有的 Apache WebServer，使用官方 XML-RPC 服务端完成完整调用。 */
public final class XmlRpcJavaSdkServer implements AutoCloseable {
    private final WebServer server;

    private XmlRpcJavaSdkServer() throws Exception {
        this.server = new WebServer(0, InetAddress.getByName("127.0.0.1"));
        try {
            PropertyHandlerMapping mapping = new PropertyHandlerMapping();
            mapping.addHandler("demo", XmlRpcJavaSdkService.class);
            XmlRpcServerConfigImpl config = new XmlRpcServerConfigImpl();
            config.setEnabledForExtensions(false);
            this.server.getXmlRpcServer().setHandlerMapping(mapping);
            this.server.getXmlRpcServer().setConfig(config);
            this.server.start();
        } catch (Exception | Error failure) {
            this.server.shutdown();
            throw failure;
        }
    }

    public static XmlRpcJavaSdkServer start() throws Exception {
        return new XmlRpcJavaSdkServer();
    }

    public int getPort() {
        return this.server.getPort();
    }

    @Override
    public void close() {
        this.server.shutdown();
    }
}