/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.direct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.hasor.cobble.StringUtils;
import net.hasor.example.OrderDemoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.grpc.builtin.GrpcBuiltinServicesFactory;

/** 同一接口只注册一次；TCP 和 HTTP 端点分别复用监听端口。 */
public final class DirectRpcExample {
    public static final String       SERVICE_NAME = "meteor.example.OrderDemoService";
    public static final String       UNIT_NAME    = "local";
    public static final List<String> PROTOCOLS;

    static {
        // all 模式按此顺序串联读写；首个协议负责初始写入和最终回读。
        List<String> protocols = new ArrayList<>();
        protocols.add("rsf");
        protocols.add("dubbo");
        protocols.add("thrift");
        protocols.add("hprose");
        protocols.add("soap");
        protocols.add("jsonrpc");
        protocols.add("xmlrpc");
        protocols.add("hessian");
        protocols.add("grpc");
        protocols.add("grpc-web");
        protocols.add("connect");
        protocols.add("triple");
        PROTOCOLS = List.copyOf(protocols);
    }

    private DirectRpcExample() {
    }

    public static MetConfiguration server(String host, int tcpPort, int httpPort) {
        MetConfiguration configuration = new MetConfiguration().setUnitName(UNIT_NAME);
        ConnectorSettings tcp = configuration.connector("tcp").bind(host, tcpPort);
        tcp.protocol("rsf");
        tcp.protocol("dubbo");
        tcp.protocol("thrift");

        // 九个 HTTP 协议挂载到同一个端点，路径选择具体的协议解析器。
        ConnectorSettings http = configuration.connector("http").bind(host, httpPort);
        for (String protocol : PROTOCOLS) {
            if (!isTcp(protocol)) {
                mount(http, protocol);
            }
        }
        // 内置服务使用标准根路径，业务 gRPC 服务仍发布在 /grpc。
        configuration.addExtension(new GrpcBuiltinServicesFactory());
        return configuration;
    }

    public static MetConfiguration client() {
        MetConfiguration configuration = new MetConfiguration().setUnitName(UNIT_NAME);
        ConnectorSettings tcp = configuration.connector("tcp");
        tcp.protocol("rsf");
        tcp.protocol("dubbo");
        tcp.protocol("thrift");

        // 出口不调用 bind；HTTP/1 和 HTTP/2 的选项属于各自的传输端点。
        ConnectorSettings http1 = configuration.connector("http");
        ConnectorSettings http2 = configuration.connector("http").option("httpVersion", 2);
        for (String protocol : PROTOCOLS) {
            if (!isTcp(protocol)) {
                ConnectorSettings outgoing = requiresHttp2(protocol) ? http2 : http1;
                mount(outgoing, protocol);
            }
        }
        return configuration;
    }

    public static MetConfiguration client(String protocol) {
        String selected = selectProtocol(protocol);
        MetConfiguration configuration = new MetConfiguration().setUnitName(UNIT_NAME);
        if (isTcp(selected)) {
            configuration.connector("tcp").protocol(selected);
        } else {
            ConnectorSettings http = configuration.connector("http");
            if (requiresHttp2(selected)) {
                http.option("httpVersion", 2);
            }
            mount(http, selected);
        }
        return configuration;
    }

    public static MetBindInfo<OrderDemoService> publish(MetContext context, //
            OrderDemoService implementation) {
        return publication(context, implementation).register();
    }

    public static MetBindInfo<OrderDemoService> subscribe(MetContext context) {
        return subscription(context).register();
    }

    public static MetBindInfo<OrderDemoService> subscribe(MetContext context, String protocol) {
        var subscription = context.publisher().rsfService(OrderDemoService.class);
        configure(subscription, List.of(selectProtocol(protocol)));
        return subscription.register();
    }

    public static ConfigurationBuilder<OrderDemoService> publication(MetContext context, //
            OrderDemoService implementation) {
        var publication = context.publisher().rsfService(OrderDemoService.class, implementation);
        configure(publication, PROTOCOLS);
        return publication;
    }

    public static ConfigurationBuilder<OrderDemoService> subscription(MetContext context) {
        var subscription = context.publisher().rsfService(OrderDemoService.class);
        configure(subscription, PROTOCOLS);
        return subscription;
    }

    private static void configure(ConfigurationBuilder<OrderDemoService> service, //
            List<String> protocols) {
        // 别名只描述各协议的调用名称，不改变同一个 Meteor 服务的身份。
        service.name(SERVICE_NAME);
        for (String protocol : protocols) {
            service.protocol(protocol);
        }
        service.aliasName("dubbo", OrderDemoService.class.getName());
        service.aliasName("thrift", "OrderThriftService");
        service.aliasName("hprose", "orders").aliasName("soap", "orders");
        service.aliasName("jsonrpc", "orders").aliasName("xmlrpc", "orders");
        service.aliasName("hessian", "orders");
        service.aliasName("grpc", SERVICE_NAME).aliasName("grpc-web", SERVICE_NAME);
        service.aliasName("connect", SERVICE_NAME).aliasName("triple", SERVICE_NAME);

        // Protobuf 描述由 Order、OrderQuery 的公共字段号生成，各挂载独立选择序列化方式。
    }

    private static void mount(ConnectorSettings connector, String protocol) {
        var mount = connector.protocol(protocol).contextPath("/" + protocol);
        if (StringUtils.equals(protocol, "triple")) {
            mount.option("serializeType", "Protobuf");
        } else if (StringUtils.equals(protocol, "soap") && connector.getHost() != null) {
            // 目录只属于监听端，消费端可独立选择服务发布的其他 context。
            mount.option("directoryEnabled", true).option("directoryPath", "/soap");
        } else if (StringUtils.equals(protocol, "grpc")) {
            mount.option("streamBody", true);
        }
    }

    private static boolean isTcp(String protocol) {
        return StringUtils.equals(protocol, "rsf") || StringUtils.equals(protocol, "dubbo") //
                || StringUtils.equals(protocol, "thrift");
    }

    private static boolean requiresHttp2(String protocol) {
        return StringUtils.equals(protocol, "grpc") || StringUtils.equals(protocol, "triple");
    }

    private static String selectProtocol(String protocol) {
        String selected = protocol.toLowerCase(Locale.ROOT);
        if (!PROTOCOLS.contains(selected)) {
            throw new IllegalArgumentException("Unknown demo protocol: " + protocol);
        }
        return selected;
    }
}
