/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import com.connectrpc.*;
import com.connectrpc.extensions.GoogleJavaJSONStrategy;
import com.connectrpc.impl.ProtocolClient;
import com.connectrpc.okhttp.ConnectOkHttpClient;
import com.connectrpc.protocols.NetworkProtocol;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;
import kotlin.jvm.JvmClassMappingKt;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.peer.protocol.grpc.GrpcSdkContract;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Timeout;

/** 同进程直接调用官方 JVM 客户端与 Armeria 服务端，分别和 Meteor 的 gRPC-Web 端点互通。 */
public class GrpcWebJavaSdkBasicTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialJavaSdkCallsPublishedJsonService() throws Exception {
        // 只挂载 gRPC-Web，不能拿普通 gRPC 的成功替代本协议可用。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("grpc-web").contextPath("/rpc").option("streamBody", true);
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc-web", "example.Echo").serialize("Json").register();

            // 帧编码、传输和 JSON 映射使用官方 Connect JVM SDK 的 GRPC_WEB 模式。
            OkHttpClient http = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();
            try {
                String target = "http://127.0.0.1:" + server.getListenAddress("grpc-web").getPort() + "/rpc";
                var config = new ProtocolClientConfig(target, new GoogleJavaJSONStrategy(), NetworkProtocol.GRPC_WEB);
                var client = new ProtocolClient(new ConnectOkHttpClient(http), config);
                var messageType = JvmClassMappingKt.getKotlinClass(Struct.class);
                var method = new MethodSpec<Struct, Struct>("example.Echo/echo", messageType, messageType, //
                        StreamType.UNARY, Idempotency.UNKNOWN);
                String expected = "Java SDK grpc-web 中文 🙂";
                Struct request = Struct.newBuilder() //
                        .putFields("value", Value.newBuilder().setStringValue(expected).build()) //
                        .putFields("count", Value.newBuilder().setNumberValue(6).build()).build();
                var sdkCall = client.unaryBlocking(request, Collections.emptyMap(), method);
                ResponseMessage<Struct> response = sdkCall.execute();
                if (response instanceof ResponseMessage.Failure<Struct> failure) {
                    throw failure.getCause();
                }
                Struct result = ((ResponseMessage.Success<Struct>) response).getMessage();
                assertEquals(expected, result.getFieldsOrThrow("value").getStringValue());
                assertEquals(7, (int) result.getFieldsOrThrow("count").getNumberValue());
                assertEquals(1, implementation.getCalls());
            } finally {
                ExecutorService executor = http.dispatcher().executorService();
                executor.shutdownNow();
                http.connectionPool().evictAll();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "gRPC-Web 客户端执行器未在 5 秒内关闭");
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerCallsExternalJavaSdkServer() throws Exception {
        // Armeria 只接受 gRPC-Web Protobuf，不启用普通 gRPC 或手写 wire 层。
        GrpcSdkContract contract = new GrpcSdkContract();
        GrpcService sdkService = GrpcService.builder() //
                .supportedSerializationFormats(GrpcSerializationFormats.PROTO_WEB) //
                .addService(contract.service("grpc-web-sdk:")).build();
        Server server = Server.builder().http(new InetSocketAddress("127.0.0.1", 0)).service(sdkService).build();
        try {
            server.start().get(10, TimeUnit.SECONDS);
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
            configuration.connector("http").option("httpVersion", 1) //
                    .protocol("grpc-web").option("streamBody", true);
            GrpcProtoResource resource = new GrpcProtoResource(getClass().getResource("/proto/echo.proto"));
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class) //
                        .aliasName("grpc-web", "example.Echo").serialize("Protobuf") //
                        .bindResource("grpc-web", resource).register();

                // SDK 前缀与数值变化证明请求确实经过该服务；消费者无本地实现。
                InterAddress address = new InterAddress("grpc-web", "127.0.0.1", server.activeLocalPort(), "default");
                Echo remote = client.getRsfClient(address).getRemote(service);
                Message result = remote.echo(Message.of("反向 Java 🙂", 6));
                assertEquals("grpc-web-sdk:反向 Java 🙂", result.getValue());
                assertEquals(7, result.getCount());
            }
        } finally {
            server.stop().get(5, TimeUnit.SECONDS);
        }
    }
}
