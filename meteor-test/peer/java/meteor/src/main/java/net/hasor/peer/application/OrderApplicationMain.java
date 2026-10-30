/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.peer.application;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import net.hasor.example.oncrpc.OncRpcOrderBridge;
import net.hasor.example.oncrpc.OncRpcOrderService;
import net.hasor.example.oncrpc.OrderXdrCodec;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;
import net.hasor.meteor.registry.dubbo.DubboRegistryClient;
import net.hasor.meteor.registry.dubbo.DubboRegistryConfiguration;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import net.hasor.example.discovery.AddressSelectionAwaiter;

/** 测试自有的单协议应用入口；每个提供者和消费者在独立 JVM 中启动。 */
public final class OrderApplicationMain {
    private OrderApplicationMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 当前进程只启用指定协议，角色和发现方式由场景显式选择。
        String protocol = arguments[0];
        if (StringUtils.equals("grpcweb", protocol)) {
            protocol = "grpc-web";
        }
        String role = arguments[1];
        boolean provider = role.endsWith("Provider");
        String discovery = "direct";
        if (role.startsWith("NativeRegistry")) {
            discovery = "dubbo";
        } else if (arguments.length > 2) {
            discovery = arguments[2];
        }

        // 注册地址由场景分配；同场景的应用共用地址和数据范围。
        String registryAddress = null;
        String registryScope = null;
        if (!StringUtils.equals("direct", discovery)) {
            registryAddress = arguments[3];
            if (StringUtils.equals("nacos", discovery) || StringUtils.equals("dubbo", discovery)) {
                registryScope = arguments[4];
            }
        }

        // bootstrap 创建上下文，容器启动后再发布或订阅服务。
        MetConfiguration configuration = configuration(protocol, provider, discovery, registryAddress, registryScope);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<?> binding = bind(context, protocol, provider, discovery, registryScope);
            if (provider) {
                InterAddress address = context.getListenAddress(protocol);
                String host = publishHost();
                String scheme = "http";
                if (List.of("rsf", "dubbo", "thrift", "oncrpc").contains(protocol)) {
                    scheme = protocol;
                }
                System.out.println("Order provider is ready: " + scheme + "://" + host + ":" + address.getPort());
                OrderExampleUtils.awaitShutdown(context);
            } else {
                consume(context, binding, protocol, discovery);
            }
        }
    }

    private static MetConfiguration configuration(String protocol, boolean provider, String discovery,
            String registryAddress, String registryScope) throws IOException {
        MetConfiguration configuration = new MetConfiguration().setUnitName("local");
        String role = "consumer";
        if (provider) {
            role = "provider";
        }
        configuration.setApplicationName(protocol + "-order-" + role);

        // 执行框架拥有并清理整个场景目录，应用只使用其中自己的地址缓存。
        String jobDirectory = System.getenv("METEOR_TEST_JOB_DIRECTORY");
        Path dataHome;
        if (StringUtils.isBlank(jobDirectory)) {
            dataHome = Files.createTempDirectory("meteor-order-");
        } else {
            dataHome = Path.of(jobDirectory).resolve("meteor-data");
        }
        configuration.setDataHome(dataHome);

        // 注册扩展只提供发现；消费者从不接收预置业务地址。
        switch (discovery) {
            case "nacos" -> configuration.addExtension(new NacosRegistryConfiguration() //
                    .setAddress(registryAddress).setGroup(registryScope).setPublishHost(publishHost()));
            case "zookeeper" -> configuration.addExtension(new ZookeeperRegistryConfiguration() //
                    .setAddress(registryAddress).setPublishHost(publishHost()));
            case "dubbo" -> configuration.addExtension(new DubboRegistryConfiguration() //
                    .setAddress("zookeeper://" + registryAddress).setPublishHost(publishHost()));
            case "direct" -> { }
            default -> throw new IllegalArgumentException("Unknown discovery: " + discovery);
        }

        // TCP 与 HTTP 分别组装，场景通过环境传入独立监听端口和可达的发布主机。
        boolean tcp = List.of("rsf", "dubbo", "thrift", "oncrpc").contains(protocol);
        String transport = "http";
        if (tcp) {
            transport = "tcp";
        }
        var connector = configuration.connector(transport);
        if (provider) {
            String host = environment("METEOR_ORDER_BIND_HOST", "127.0.0.1");
            int port = Integer.parseInt(environment("METEOR_ORDER_PORT", "8080"));
            connector.bind(host, port);
        }
        if (StringUtils.equals("grpc", protocol) || StringUtils.equals("triple", protocol)) {
            connector.option("httpVersion", 2);
        }
        var mount = connector.protocol(protocol);
        if (!tcp) {
            String path = "/" + protocol;
            if (StringUtils.equals("triple", protocol)) {
                path = "/";
            }
            mount.contextPath(path);
        }
        if (usesProtobuf(protocol)) {
            mount.option("serializeType", "Protobuf");
        }
        if (StringUtils.equals("grpc", protocol)) {
            mount.option("streamBody", true);
        }
        if (StringUtils.equals("grpc-web", protocol)) {
            mount.option("corsOrigins", environment("METEOR_ORDER_CORS_ORIGINS", "*"));
        }

        // 注册客户端的管理 RPC 使用独立 RSF 挂载，业务服务仍只发布到当前协议。
        boolean managedRegistry = StringUtils.equals("nacos", discovery)
                || StringUtils.equals("zookeeper", discovery);
        if (managedRegistry && !StringUtils.equals("rsf", protocol)) {
            var management = configuration.connector("tcp");
            if (provider) {
                management.bind(environment("METEOR_ORDER_BIND_HOST", "127.0.0.1"), 0);
            }
            management.protocol("rsf");
        }
        return configuration;
    }

    private static MetBindInfo<?> bind(MetContext context, String protocol, boolean provider, String discovery,
            String registryScope) throws IOException {
        // ONC RPC 使用标量查询签名，桥接到同一份测试订单实现。
        if (StringUtils.equals("oncrpc", protocol)) {
            ConfigurationBuilder<OncRpcOrderService> builder;
            if (provider) {
                var implementation = new OncRpcOrderBridge(new OrderDemoServiceImpl());
                builder = context.publisher().rsfService(OncRpcOrderService.class, implementation);
            } else {
                builder = context.publisher().rsfService(OncRpcOrderService.class);
            }
            var resource = new OncRpcServiceResource(OncRpcOrderService.class) //
                    .codec(Order.class, new OrderXdrCodec());
            return builder.name("meteor.example.idl.oncrpc.Order").protocol(protocol) //
                    .bindResource(protocol, resource).register();
        }

        // 普通接口与协议别名分别确定 Java 身份和线上服务名称。
        ConfigurationBuilder<OrderDemoService> builder;
        if (provider) {
            builder = context.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl());
        } else {
            builder = context.publisher().rsfService(OrderDemoService.class);
        }
        builder.name(OrderExampleUtils.SERVICE_NAME).protocol(protocol);
        if (StringUtils.equals("dubbo", discovery)) {
            builder.name(OrderDemoService.class.getName()).group(registryScope).version("1.0.0");
        }
        if (StringUtils.equals("dubbo", protocol)) {
            builder.aliasName(protocol, OrderDemoService.class.getName());
        } else if (StringUtils.equals("thrift", protocol)) {
            builder.aliasName(protocol, "OrderThriftService");
        } else if (usesProtobuf(protocol)) {
            URL contract = OrderApplicationMain.class.getResource("/protocol/proto/order.proto");
            builder.aliasName(protocol, OrderExampleUtils.SERVICE_NAME) //
                    .bindResource(protocol, new GrpcProtoResource(contract));
        } else if (StringUtils.equals("hprose", protocol)) {
            builder.aliasName("Hprose", "orders");
        } else if (!StringUtils.equals("rsf", protocol)) {
            builder.aliasName(protocol, "orders");
        }
        return builder.register();
    }

    private static void consume(MetContext context, MetBindInfo<?> binding, String protocol, String discovery)
            throws Exception {
        // 创建消费代理，原生 Dubbo 发现由 reference 显式建立订阅。
        boolean direct = StringUtils.equals("direct", discovery);
        MetClient client = context.getRsfClient();
        if (direct) {
            URI target = URI.create(environment("METEOR_ORDER_TARGET", protocol + "://127.0.0.1:8080"));
            client = context.getRsfClient(new InterAddress(protocol, target.getHost(), target.getPort(), "local"));
        }
        Object remote;
        if (StringUtils.equals("dubbo", discovery)) {
            DubboRegistryClient registry = context.getExtension(DubboRegistryClient.class);
            @SuppressWarnings("unchecked")
            MetBindInfo<OrderDemoService> typedBinding = (MetBindInfo<OrderDemoService>) binding;
            remote = registry.reference(typedBinding);
        } else {
            remote = client.getRemote(binding);
        }

        // 自动发现只等待正式选址就绪，不注入地址，也不发送探测业务 RPC。
        if (!direct) {
            AddressSelectionAwaiter.await(context, binding, "save");
            var addresses = context.getUpdater().queryAvailableAddresses(binding.getBindID());
            System.out.println("Discovered addresses: " + addresses);
        }
        String orderProtocol = protocol;
        if (StringUtils.equals("dubbo", discovery)) {
            orderProtocol = "dubbo-registry";
        }
        Order order = OrderExampleUtils.newOrder(1001, orderProtocol);

        // 所有结果都来自独立提供者，ONC RPC 保留自己的查询参数形式。
        if (StringUtils.equals("oncrpc", protocol)) {
            var orders = (OncRpcOrderService) remote;
            orders.save(order);
            OrderExampleUtils.print(orders.find(order.getId()));
        } else {
            OrderDemoService orders = (OrderDemoService) remote;
            orders.save(order);
            OrderQuery query = new OrderQuery();
            query.setId(order.getId());
            OrderExampleUtils.print(orders.find(query));
        }
    }

    private static String publishHost() {
        return environment("METEOR_ORDER_PUBLISH_HOST", environment("METEOR_TEST_PEER_HOST", "127.0.0.1"));
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return StringUtils.isBlank(value) ? fallback : value;
    }

    private static boolean usesProtobuf(String protocol) {
        return List.of("grpc", "grpc-web", "connect", "triple").contains(protocol);
    }
}
