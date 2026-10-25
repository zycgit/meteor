/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.matrix;
import java.io.IOException;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.oncrpc.OncRpcOrderBridge;
import net.hasor.example.oncrpc.OncRpcOrderService;
import net.hasor.example.oncrpc.OrderXdrCodec;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.MetPublisher.LinkedBuilder;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;

/** 统一订单契约的协议绑定；仅 ONC RPC 使用签名桥接，其他协议直接复用普通接口和 DTO。 */
public enum OrderProtocol {
    RSF("rsf", "tcp"),
    GRPC("grpc", "http"),
    GRPC_WEB("grpc-web", "http"),
    HPROSE("hprose", "http"),
    JSONRPC("jsonrpc", "http"),
    XMLRPC("xmlrpc", "http"),
    SOAP("soap", "http"),
    HESSIAN("hessian", "http"),
    CONNECT("connect", "http"),
    TRIPLE("triple", "http"),
    DUBBO("dubbo", "tcp"),
    THRIFT("thrift", "tcp"),
    ONCRPC("oncrpc", "tcp");

    private final String name;
    private final String transport;

    OrderProtocol(String name, String transport) {
        this.name = name;
        this.transport = transport;
    }

    public String protocolName() {
        return this.name;
    }

    public MetConfiguration providerConfiguration() {
        return this.configuration(true);
    }

    public MetConfiguration consumerConfiguration() {
        return this.configuration(false);
    }

    private MetConfiguration configuration(boolean listen) {
        // 业务端点只安装当前协议；是否监听与是否具备出站能力分别配置。
        MetConfiguration configuration = new MetConfiguration().setUnitName(DirectRpcExample.UNIT_NAME);
        ConnectorSettings connector = configuration.connector(this.transport);
        if (listen) {
            connector.bind("127.0.0.1", 0);
        }
        if (this == GRPC || this == TRIPLE) {
            connector.option("httpVersion", 2);
        }

        this.mount(connector);
        return configuration;
    }

    public void mount(ConnectorSettings connector) {
        // 组合用例可向同一 HTTP 端点挂载多个协议；此方法不改变传输端口与 HTTP 版本。
        var mount = connector.protocol(this.name);
        if (this.transportIsHttp()) {
            mount.contextPath("/" + this.name);
        }
        if (this.usesProtobuf()) {
            mount.option("serializeType", "Protobuf");
        }
    }

    public MetBindInfo<?> publish(MetContext context, OrderDemoService implementation) throws IOException {
        // ONC RPC 绑定显式 XDR 契约，桥接对象委托到传入的同一份业务实例。
        if (this == ONCRPC) {
            OncRpcOrderBridge bridge = new OncRpcOrderBridge(implementation);
            var builder = context.publisher().rsfService(OncRpcOrderService.class, bridge);
            return this.configure(builder).register();
        }

        // 普通接口保持现有用户示例的服务身份和业务实现。
        var builder = context.publisher().rsfService(OrderDemoService.class, implementation);
        return this.configure(builder).register();
    }

    public MetBindInfo<?> subscribe(MetContext context) throws IOException {
        if (this == ONCRPC) {
            LinkedBuilder<OncRpcOrderService> builder = context.publisher().rsfService(OncRpcOrderService.class);
            return this.configure(builder).register();
        }
        LinkedBuilder<OrderDemoService> builder = context.publisher().rsfService(OrderDemoService.class);
        return this.configure(builder).register();
    }

    public <T> ConfigurationBuilder<T> configure(ConfigurationBuilder<T> builder) throws IOException {
        // 只有 ONC RPC 使用独立签名；Thrift 注解与其他协议共享普通接口及服务 ID。
        String serviceName = DirectRpcExample.SERVICE_NAME;
        if (this == ONCRPC) {
            serviceName = "meteor.example.idl.oncrpc.Order";
        }
        builder.name(serviceName).protocol(this.name).timeout(5000);

        // 协议别名选择线上服务身份，不改变注册库中统一的服务名称。
        if (this == DUBBO) {
            builder.aliasName(this.name, OrderDemoService.class.getName());
        } else if (this == THRIFT) {
            builder.aliasName(this.name, "OrderThriftService");
        } else if (this.usesProtobuf()) {
            builder.aliasName(this.name, DirectRpcExample.SERVICE_NAME);
        } else if (this.transportIsHttp()) {
            builder.aliasName(this.name, "orders");
        }

        // 资源由每次绑定创建并持有，避免不同协议修改共享绑定描述。
        if (this == ONCRPC) {
            OncRpcServiceResource resource = new OncRpcServiceResource(OncRpcOrderService.class) //
                    .codec(Order.class, new OrderXdrCodec());
            builder.bindResource(this.name, resource);
        } else if (this.usesProtobuf()) {
            var proto = DirectRpcExample.class.getResource("/order/order.proto");
            builder.bindResource(this.name, new GrpcProtoResource(proto));
        }
        return builder;
    }

    public Order save(MetClient client, MetBindInfo<?> subscription, Order order) throws Exception {
        if (this == ONCRPC) {
            var remote = (OncRpcOrderService) client.getRemote(subscription);
            return remote.save(order);
        }
        var remote = (OrderDemoService) client.getRemote(subscription);
        return remote.save(order);
    }

    public Order find(MetClient client, MetBindInfo<?> subscription, int id) throws Exception {
        // ONC RPC 使用标量查询；Thrift 与其他协议直接传递同一个查询对象。
        if (this == ONCRPC) {
            var remote = (OncRpcOrderService) client.getRemote(subscription);
            return remote.find(id);
        }
        OrderQuery query = new OrderQuery();
        query.setId(id);
        var remote = (OrderDemoService) client.getRemote(subscription);
        return remote.find(query);
    }

    private boolean transportIsHttp() {
        return this != RSF && this != DUBBO && this != THRIFT && this != ONCRPC;
    }

    public boolean usesProtobuf() {
        return this == GRPC || this == GRPC_WEB || this == CONNECT || this == TRIPLE;
    }

    @Override
    public String toString() {
        return this.name;
    }
}
