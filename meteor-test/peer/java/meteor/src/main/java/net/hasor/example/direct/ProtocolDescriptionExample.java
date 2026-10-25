/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.direct;
import java.io.IOException;
import java.net.URL;
import net.hasor.example.OrderDemoService;
import net.hasor.example.oncrpc.OncRpcOrderBridge;
import net.hasor.example.oncrpc.OncRpcOrderService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.builtin.GrpcBuiltinServicesFactory;
import net.hasor.meteor.protocol.grpc.builtin.GrpcHealthService;
import net.hasor.meteor.protocol.grpc.builtin.GrpcReflectionService;

/** 普通 Java 接口的契约生成、文件绑定及 gRPC 标准服务订阅示例。 */
public final class ProtocolDescriptionExample {
    private ProtocolDescriptionExample() {
    }

    public static MetBindInfo<GrpcHealthService> subscribeHealth(MetContext context) throws IOException {
        var subscription = context.publisher().rsfService(GrpcHealthService.class);
        subscription.name("grpc.health.v1.Health").aliasName("grpc", "grpc.health.v1.Health") //
                .protocol("grpc").serialize("Protobuf").bindResource("grpc", standardResource());

        // 标准服务方法采用完整根路径，不继承业务协议的 /grpc 上下文。
        subscription.contextPathByExact("grpc", "/grpc.health.v1.Health/Check", //
                "/grpc.health.v1.Health/List", "/grpc.health.v1.Health/Watch");
        return subscription.register();
    }

    public static MetBindInfo<GrpcReflectionService> subscribeReflection(MetContext context) throws IOException {
        String name = "grpc.reflection.v1.ServerReflection";
        var subscription = context.publisher().rsfService(GrpcReflectionService.class);
        subscription.name(name).aliasName("grpc", name).protocol("grpc") //
                .serialize("Protobuf").bindResource("grpc", standardResource());

        // Reflection 双向流同样使用 gRPC 标准路径，与业务挂载保持独立。
        subscription.contextPathByExact("grpc", "/grpc.reflection.v1.ServerReflection/ServerReflectionInfo");
        return subscription.register();
    }

    /** 已有契约作为消费者入口，资源只提供给选中的 Protobuf 协议。 */
    public static MetBindInfo<OrderDemoService> subscribeOrdersFromFile(MetContext context, //
            String protocol) throws IOException {
        GrpcProtoResource resource = orderResource();
        return context.publisher().rsfService(OrderDemoService.class) //
                .name(DirectRpcExample.SERVICE_NAME).protocol(protocol) //
                .aliasName(protocol, DirectRpcExample.SERVICE_NAME).serialize("Protobuf") //
                .bindResource(protocol, resource).register();
    }

    /** 相同 Java 接口由公共注解生成契约，消费者无需提供 proto 文件。 */
    public static MetBindInfo<OrderDemoService> subscribeOrdersByAnnotations(MetContext context, String protocol) {
        return context.publisher().rsfService(OrderDemoService.class) //
                .name(DirectRpcExample.SERVICE_NAME).protocol(protocol) //
                .aliasName(protocol, DirectRpcExample.SERVICE_NAME).serialize("Protobuf").register();
    }

    /** 提供者也可以绑定已有文件，公共描述负责校验 Java 接口与文件的映射。 */
    public static MetBindInfo<OrderDemoService> publishOrdersFromFile(MetContext context, //
            OrderDemoService implementation, String protocol) throws IOException {
        return context.publisher().rsfService(OrderDemoService.class, implementation) //
                .name(DirectRpcExample.SERVICE_NAME).protocol(protocol) //
                .aliasName(protocol, DirectRpcExample.SERVICE_NAME).serialize("Protobuf") //
                .bindResource(protocol, orderResource()).register();
    }

    /** Order 的公共字段号确定自动 XDR 成员顺序，无需专属字段顺序注解或自定义 Codec。 */
    public static MetBindInfo<OncRpcOrderService> publishOrdersByXdrFields(MetContext context, //
            OrderDemoService implementation) {
        // 程序号和过程号由 ONC 接口声明，订单字段仍复用多协议共同的 MetField。
        OncRpcOrderBridge provider = new OncRpcOrderBridge(implementation);
        return context.publisher().rsfService(OncRpcOrderService.class, provider) //
                .name(OncRpcOrderService.class.getName()).protocol("oncrpc").register();
    }

    private static GrpcProtoResource orderResource() throws IOException {
        URL source = ProtocolDescriptionExample.class.getResource("/order/order.proto");
        return new GrpcProtoResource(source);
    }

    private static GrpcProtoResource standardResource() throws IOException {
        String resource = "/META-INF/meteor/protocol/grpc/meteor-grpc-services.proto";
        URL source = GrpcBuiltinServicesFactory.class.getResource(resource);
        return new GrpcProtoResource(source);
    }
}
