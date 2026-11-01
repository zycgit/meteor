/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.google.protobuf.Empty;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import net.hasor.example.protocol.triple.TripleAmbiguousService;
import net.hasor.example.protocol.triple.TripleAmbiguousServiceImpl;
import net.hasor.example.protocol.triple.TripleFlowService;
import net.hasor.example.protocol.triple.TripleFlowServiceImpl;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.example.protocol.triple.TripleNotificationService;
import net.hasor.example.protocol.triple.TripleUnaryService;
import net.hasor.example.protocol.triple.TripleUnaryServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 协议不支持的声明只跳过 Triple 挂载，本地服务仍保留。 */
@NxnRpc(name = "triple")
public class TriplePublicationScenarioTest {
    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void notificationRemainsCallableThroughTheLocalClient() throws Exception {
        // 先正常启动 Triple 端点，再尝试发布无响应消息方法。
        MetConfiguration configuration = this.configuration("grpc");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            CountDownLatch invoked = new CountDownLatch(1);
            TripleNotificationService implementation = request -> invoked.countDown();

            // Triple 不将 notification 当成普通 RPC，只有当前端点被拒绝。
            MetBindInfo<TripleNotificationService> service = context.publisher() //
                    .rsfService(TripleNotificationService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleNotificationService") //
                    .serialize("Hessian2").register();
            assertNotNull(context.getServiceInfo(service.getBindID()));

            // 公开本地代理仍可投递消息，不直接调用业务实现自证。
            TripleNotificationService local = context.getRsfClient().getRemote(service);
            local.notify(TripleMessage.of("local notice", 1));
            assertTrue(invoked.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void protobufWithoutSchemaRemainsLocalAndIsUnavailableOverTriple() throws Exception {
        // Protobuf 显式依赖绑定的 proto 资源，普通 Java wrapper 则不需要。
        MetConfiguration configuration = this.configuration("grpc");
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 缺少绑定资源时不开放当前协议端点，本地声明仍成功注册。
            MetBindInfo<TripleUnaryService> service = context.publisher() //
                    .rsfService(TripleUnaryService.class, new TripleUnaryServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf").register();
            assertNotNull(context.getServiceInfo(service.getBindID()));
            TripleUnaryService local = context.getRsfClient().getRemote(service);
            TripleMessage result = local.echo(TripleMessage.of("local", 6));
            assertEquals("local", result.getText());
            assertEquals(7, result.getNumber());

            // 官方客户端发出有效 gRPC 请求，拒绝发布的服务按协议返回 UNIMPLEMENTED。
            int port = context.getListenAddress("triple").getPort();
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                MethodDescriptor<Empty, Empty> method = MethodDescriptor.<Empty, Empty>newBuilder() //
                        .setType(MethodDescriptor.MethodType.UNARY) //
                        .setFullMethodName("rpc/meteor.triple.demo.TripleUnaryService/echo") //
                        .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance())) //
                        .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance())) //
                        .build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS);
                StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () -> {
                    ClientCalls.blockingUnaryCall(channel, method, options, Empty.getDefaultInstance());
                });
                assertEquals(Status.Code.UNIMPLEMENTED, failure.getStatus().getCode());
            } finally {
                channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void flowOverPlainHttpRemainsCallableThroughTheLocalClient() throws Exception {
        // 普通 HTTP JSON 属于 unary 子协议，流式模式应选择 gRPC framing。
        MetConfiguration configuration = this.configuration("json");
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 普通 HTTP 无法承载 Flow 时只跳过当前协议端点。
            MetBindInfo<TripleFlowService> service = context.publisher() //
                    .rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json").register();
            assertNotNull(context.getServiceInfo(service.getBindID()));

            // 本地调用保留 Java Flow 语义，按需求消费完整消息流。
            TripleFlowService local = context.getRsfClient().getRemote(service);
            List<TripleMessage> messages = new ArrayList<>();
            MetCaller.consume(local.count(TripleMessage.of("local", 2)), messages::add).get(3, TimeUnit.SECONDS);
            assertEquals(2, messages.size());
            assertEquals("local", messages.get(0).getText());
            assertEquals(0, messages.get(0).getNumber());
            assertEquals(1, messages.get(1).getNumber());
        }
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void indistinguishableWireSignaturesRemainCallableThroughTheLocalClient() throws Exception {
        // Java 的 String 与 Flow<String> 参数不同，但 wrapper 中都只声明 String 消息。
        MetConfiguration configuration = this.configuration("grpc");
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 歧义签名不进入 Triple 端点，服务仍可作为本地声明存在。
            MetBindInfo<TripleAmbiguousService> service = context.publisher() //
                    .rsfService(TripleAmbiguousService.class, new TripleAmbiguousServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleAmbiguousService") //
                    .serialize("Hessian2").register();
            assertNotNull(context.getServiceInfo(service.getBindID()));

            // 本地代理按真实 Java 参数类型选择重载，无需线协议签名消歧。
            TripleAmbiguousService local = context.getRsfClient().getRemote(service);
            assertEquals("local", local.exchange("local"));
            assertEquals("stream", local.exchange(MetCaller.fromIterable(List.of("item"))));
        }
    }

    private MetConfiguration configuration(String mode) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.connector("http").bind("127.0.0.1", 0).option("httpVersion", 2) //
                .protocol("triple").contextPath("/rpc").option("httpMode", mode).option("streamBody", true);
        return configuration;
    }
}
