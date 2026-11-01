/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import net.hasor.example.protocol.grpc.AnnotatedEcho;
import net.hasor.example.protocol.grpc.AnnotatedEchoImpl;
import net.hasor.example.protocol.grpc.AnnotatedMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

public class GrpcAnnotationScenarioTest {
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void annotationProviderAcceptsAFileBasedConsumer() throws Exception {
        // 服务端由注解生成描述，客户端按已有 proto 绑定相同 Java 接口。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        GrpcProtoResource resource = this.resource();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(AnnotatedEcho.class, new AnnotatedEchoImpl()) //
                    .aliasName("grpc", "example.Echo").serialize("Protobuf").register();
            MetBindInfo<AnnotatedEcho> service = client.publisher().rsfService(AnnotatedEcho.class) //
                    .aliasName("grpc", "example.Echo").serialize("Protobuf") //
                    .bindResource("grpc", resource).register();

            // 继承接口的类型变量在发布时解析，调用仍使用原始 Java 方法名。
            AnnotatedEcho remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(service);
            AnnotatedMessage input = this.message();
            AnnotatedMessage response = remote.mirror(input);
            assertEquals("annotation", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void annotationConsumerCallsAFileBasedProvider() throws Exception {
        // 提供者使用已有契约，消费者只提供公共注解和普通 Java DTO。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        GrpcProtoResource resource = this.resource();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(AnnotatedEcho.class, new AnnotatedEchoImpl()) //
                    .aliasName("grpc", "example.Echo").serialize("Protobuf") //
                    .bindResource("grpc", resource).register();
            MetBindInfo<AnnotatedEcho> service = client.publisher().rsfService(AnnotatedEcho.class) //
                    .aliasName("grpc", "example.Echo").serialize("Protobuf").register();

            // 编解码均使用准备结果，无需 DTO 生成代码。
            AnnotatedEcho remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(service);
            AnnotatedMessage response = remote.mirror(this.message());
            assertEquals("annotation", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    private MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        return configuration;
    }

    private MetConfiguration clientConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.connector("http").option("httpVersion", 2).protocol("grpc");
        return configuration;
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
    }

    private AnnotatedMessage message() {
        AnnotatedMessage input = new AnnotatedMessage();
        input.setValue("annotation");
        input.setCount(7);
        return input;
    }
}
