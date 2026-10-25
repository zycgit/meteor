/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.adapter;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 官方 Java gRPC SDK 的显式存储适配发现；沿用既有同 JVM Netty 对端，业务经真实 HTTP/2 传输。 */
@RunWith(Parameterized.class)
public class GrpcJavaRegistryAdapterScenarioTest {
    private static final String         SERVICE  = "meteor.example.OrderDemoService";
    private static final ObjectMapper   JSON     = new ObjectMapper();
    private static final FileDescriptor CONTRACT = contract();
    private static final Descriptor     ORDER    = CONTRACT.findMessageTypeByName("Order");
    private static final Descriptor     QUERY    = CONTRACT.findMessageTypeByName("OrderQuery");

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;

    public GrpcJavaRegistryAdapterScenarioTest(String backend) {
        this.backend = backend;
    }

    @Parameterized.Parameters(name = "grpc/java/adapter/{0}")
    public static Object[][] backends() {
        return new Object[][] { { "jdbc" }, { "zookeeper" }, { "nacos" } };
    }

    @Test(timeout = 180000)
    public void officialJavaConsumerResolvesMeteorProviderThroughStorageAdapter() throws Exception {
        // Meteor 只通过 bootstrap 发布普通订单接口，注册扩展上报实际端点。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "java-grpc-provider", true);
            try (MetContext provider = configuration.buildContext()) {
                provider.start();
                MetBindInfo<OrderDemoService> service = provider.publisher() //
                        .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                        .serialize("Protobuf").bindResource("grpc", this.resource()).register();

                // 官方客户端仅从公开存储订阅中取得地址，不读取 Meteor 监听端口。
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, service.getBindID(), "grpc")) {
                    URI target = resolver.awaitCount(1).get(0);
                    assertEquals("http", target.getScheme());
                    assertEquals("/", target.getPath());
                    ManagedChannel channel = NettyChannelBuilder.forAddress(target.getHost(), target.getPort()) //
                            .usePlaintext().build();
                    try {
                        // 官方 Protobuf marshaller 根据固定标准字段编码，两次 RPC 由 SDK 直接发送。
                        Order expected = this.order();
                        CallOptions deadline = CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS);
                        var save = method("save", ORDER);
                        DynamicMessage request = message(expected);
                        DynamicMessage saved = ClientCalls.blockingUnaryCall(channel, save, deadline, request);
                        DynamicMessage query = DynamicMessage.newBuilder(QUERY) //
                                .setField(QUERY.findFieldByName("id"), expected.getId()).build();
                        var find = method("find", QUERY);
                        DynamicMessage found = ClientCalls.blockingUnaryCall(channel, find, deadline, query);
                        assertOrder(expected, order(saved));
                        assertOrder(expected, order(found));
                    } finally {
                        channel.shutdownNow();
                        assertTrue(channel.awaitTermination(5, TimeUnit.SECONDS));
                    }
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversOfficialJavaProviderThroughStorageAdapter() throws Exception {
        // 消费者只配置出站 HTTP/2 和注册库，订阅时没有指定目标地址。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "java-grpc-consumer", false);
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<OrderDemoService> service = consumer.publisher().rsfService(OrderDemoService.class) //
                        .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                        .serialize("Protobuf").bindResource("grpc", this.resource()).register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 沿用现有同 JVM 官方 Netty 服务端，start 返回后才登记其真实监听端口。
                OrderDemoService implementation = new OrderDemoServiceImpl();
                Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)) //
                        .addService(officialService(implementation)).build().start();
                try (ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress("http", "127.0.0.1", server.getPort(), unit);
                    registrar.register(service, ProcessHandle.current().pid(), "java", "grpc", "http", address, "/");
                    AddressSelectionAwaiter.await(consumer, service, "save");

                    // Meteor 使用自动地址本发起调用，官方服务端持有独立的统一订单业务实例。
                    OrderDemoService orders = consumer.getRsfClient().getRemote(service);
                    Order expected = this.order();
                    Order saved = orders.save(expected);
                    OrderQuery query = new OrderQuery();
                    query.setId(expected.getId());
                    Order found = orders.find(query);
                    assertOrder(expected, saved);
                    assertOrder(expected, found);
                } finally {
                    server.shutdownNow();
                    assertTrue(server.awaitTermination(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String application, boolean provider) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc").setDefaultTimeout(10000);
        configuration.setApplicationName(application);
        configuration.setDataHome(this.data.newFolder(application).toPath());
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 内部管理出口不开放监听。
        var http = configuration.connector("http").option("httpVersion", 2);
        if (provider) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("grpc").addressScheme("http").contextPath("/");
        return configuration;
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/order/order.proto"));
    }

    private Order order() throws Exception {
        try (InputStream input = this.getClass().getResourceAsStream("/vectors/registry/order.json")) {
            return JSON.readValue(input, Order.class);
        }
    }

    private static ServerServiceDefinition officialService(OrderDemoService implementation) {
        // 官方 SDK 负责消息解码、方法分派和响应编码，处理器只做标准消息与业务 DTO 的转换。
        var save = ServerCalls.<DynamicMessage, DynamicMessage>asyncUnaryCall((request, reply) -> {
            reply.onNext(message(implementation.save(order(request))));
            reply.onCompleted();
        });
        var find = ServerCalls.<DynamicMessage, DynamicMessage>asyncUnaryCall((request, reply) -> {
            OrderQuery query = new OrderQuery();
            query.setId((Integer) request.getField(QUERY.findFieldByName("id")));
            reply.onNext(message(implementation.find(query)));
            reply.onCompleted();
        });
        return ServerServiceDefinition.builder(SERVICE) //
                .addMethod(method("save", ORDER), save) //
                .addMethod(method("find", QUERY), find).build();
    }

    private static MethodDescriptor<DynamicMessage, DynamicMessage> method(String name, Descriptor request) {
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder() //
                .setType(MethodDescriptor.MethodType.UNARY) //
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, name)) //
                .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(request))) //
                .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(ORDER))).build();
    }

    private static DynamicMessage message(Order order) {
        return DynamicMessage.newBuilder(ORDER) //
                .setField(ORDER.findFieldByName("id"), order.getId()) //
                .setField(ORDER.findFieldByName("description"), order.getDescription()) //
                .setField(ORDER.findFieldByName("attachment"), ByteString.copyFrom(order.getAttachment())).build();
    }

    private static Order order(DynamicMessage message) {
        Order order = new Order();
        order.setId((Integer) message.getField(ORDER.findFieldByName("id")));
        order.setDescription((String) message.getField(ORDER.findFieldByName("description")));
        ByteString attachment = (ByteString) message.getField(ORDER.findFieldByName("attachment"));
        order.setAttachment(attachment.toByteArray());
        return order;
    }

    private static void assertOrder(Order expected, Order actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getDescription(), actual.getDescription());
        assertArrayEquals(expected.getAttachment(), actual.getAttachment());
    }

    private static FileDescriptor contract() {
        // 官方 SDK 的固定契约与公开 order.proto 保持一致，禁止借用 Meteor 编解码器产生对端消息。
        DescriptorProto order = DescriptorProto.newBuilder().setName("Order") //
                .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_INT32)) //
                .addField(field("description", 2, FieldDescriptorProto.Type.TYPE_STRING)) //
                .addField(field("attachment", 3, FieldDescriptorProto.Type.TYPE_BYTES)).build();
        DescriptorProto query = DescriptorProto.newBuilder().setName("OrderQuery") //
                .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_INT32)).build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder().setName("order.proto") //
                .setPackage("meteor.example").setSyntax("proto3") //
                .addMessageType(order).addMessageType(query).build();
        try {
            return FileDescriptor.buildFrom(file, new FileDescriptor[0]);
        } catch (DescriptorValidationException invalidContract) {
            throw new ExceptionInInitializerError(invalidContract);
        }
    }

    private static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type).build();
    }
}
