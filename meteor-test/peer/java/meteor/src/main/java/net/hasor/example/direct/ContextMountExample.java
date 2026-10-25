/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.direct;
import java.io.IOException;
import java.util.Arrays;
import net.hasor.cobble.StringUtils;
import net.hasor.example.OrderDemoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.bootstrap.MetConfiguration.ProtocolSettings;

/** 一个 HTTP 监听上的默认、指定和多个 SOAP context；其他协议继续复用同一业务服务。 */
public final class ContextMountExample {
    private ContextMountExample() {
    }

    public static MetConfiguration server(String host, int tcpPort, int httpPort) {
        MetConfiguration configuration = DirectRpcExample.server(host, tcpPort, httpPort);
        for (ConnectorSettings connector : configuration.getConnectors()) {
            for (ProtocolSettings protocol : connector.getProtocols()) {
                if (StringUtils.equals("soap", protocol.getName())) {
                    // /soap 保留为默认，额外路径只供服务发布时显式选择。
                    protocol.addContextPath("/private").addContextPath("/partner");
                }
            }
        }
        return configuration;
    }

    public static MetConfiguration client(String soapContextPath) {
        MetConfiguration configuration = DirectRpcExample.client();
        for (ConnectorSettings connector : configuration.getConnectors()) {
            for (ProtocolSettings protocol : connector.getProtocols()) {
                if (StringUtils.equals("soap", protocol.getName())) {
                    protocol.contextPath(soapContextPath);
                }
            }
        }
        return configuration;
    }

    /** 不传路径时使用默认 /soap；指定路径后只在这些 context 发布 SOAP 服务。 */
    public static MetBindInfo<OrderDemoService> publish(MetContext context, OrderDemoService implementation, //
            String... soapContextPaths) throws IOException {
        var publication = DirectRpcExample.publication(context, implementation);
        if (soapContextPaths.length > 0) {
            String primary = soapContextPaths[0];
            String[] additional = Arrays.copyOfRange(soapContextPaths, 1, soapContextPaths.length);
            publication.contextPathByPrefix("soap", primary, additional);
        }
        return publication.register();
    }
}
