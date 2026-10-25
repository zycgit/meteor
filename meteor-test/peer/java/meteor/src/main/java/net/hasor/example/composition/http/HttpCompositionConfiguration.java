/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.composition.http;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;

/** 同一个 HTTP 端点上静态挂载十种真实协议插件。 */
public final class HttpCompositionConfiguration {
    private HttpCompositionConfiguration() {
    }

    public static MetConfiguration incoming() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        ConnectorSettings connector = configuration.connector("http").bind("127.0.0.1", 0);
        mount(connector);
        return configuration;
    }

    public static MetConfiguration outgoing(int httpVersion) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        ConnectorSettings connector = configuration.connector("http").option("httpVersion", httpVersion);
        mount(connector);
        return configuration;
    }

    public static void mount(ConnectorSettings connector) {
        connector.protocol("hprose").contextPath("/hprose");
        connector.protocol("soap").contextPath("/soap");
        connector.protocol("jsonrpc").contextPath("/jsonrpc");
        connector.protocol("xmlrpc").contextPath("/xmlrpc");
        connector.protocol("hessian").contextPath("/hessian");
        connector.protocol("grpc").contextPath("/grpc");
        connector.protocol("grpc-web").contextPath("/grpc-web");
        connector.protocol("connect").contextPath("/connect");
        connector.protocol("triple").contextPath("/triple");
        connector.protocol("thrift-http").contextPath("/thrift");
    }
}
