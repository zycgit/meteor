/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.scenario;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetContextEventType;
import net.hasor.meteor.MetPublisher.ConfigurationBuilder;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.scenario.example.Echo;
import net.hasor.meteor.protocol.grpc.scenario.example.EchoImpl;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcPublicationTest {
    @Test
    public void missingDefaultProtoRejectsPublicationAndCanBeCorrected() throws Exception {
        // 观察正式注册事件，确认失败发布不会留下服务或发送成功通知。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            AtomicInteger registered = new AtomicInteger();
            context.subscribe(event -> {
                if (event.getType() == MetContextEventType.REGISTERED) {
                    registered.incrementAndGet();
                }
            });
            ConfigurationBuilder<Echo> service = context.publisher().rsfService(Echo.class, new EchoImpl());
            service.aliasName("grpc", "example.Echo");

            // 未开启监听就能发现协议默认 Protobuf 缺少资源，不能留下半注册服务。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, service::register);
            assertEquals("Protobuf service must bind proto file.", failure.getMessage());
            assertTrue(context.getServiceIDs().isEmpty());
            assertTrue(context.getServiceIDs("grpc").isEmpty());
            assertNull(context.getServiceInfo(Echo.class));
            assertEquals(0, registered.get());

            // 改正同一声明后可以正常注册，服务级 JSON 覆盖协议默认 Protobuf。
            MetBindInfo<Echo> info = service.serialize("Json").register();
            MetBindInfo<Echo> registeredService = context.getServiceInfo(Echo.class);
            assertEquals(info.getBindID(), registeredService.getBindID());
            assertEquals(1, registered.get());
            context.start();
            assertTrue(context.isOnline());
        }
    }

    @Test
    public void extensionPublicationIsCheckedWhileBuildingTheContext() {
        // 让扩展在上下文构建期间发布缺少 proto 的服务。
        MetConfiguration configuration = this.configuration();
        configuration.addExtension(context -> {
            // 扩展创建过程中发布服务，容器的协议事件监听必须已经就绪。
            context.publisher() //
                    .rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("grpc", "example.Echo") //
                    .register();
            throw new AssertionError("Missing proto must fail during publication");
        });

        // 构建失败应保留发布检查的原始原因。
        IllegalStateException failure = assertThrows(IllegalStateException.class, configuration::buildContext);
        Throwable cause = failure.getCause();
        assertTrue(cause instanceof IllegalArgumentException);
        assertEquals("Protobuf service must bind proto file.", cause.getMessage());
    }

    @Test
    public void explicitProtobufIsCheckedWhenPublishingAfterStartup() throws Exception {
        // 挂载默认选择 JSON，服务发布时显式覆盖为 Protobuf。
        MetConfiguration configuration = new MetConfiguration();
        configuration //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("serializeType", "Json");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            ConfigurationBuilder<Echo> service = context.publisher().rsfService(Echo.class, new EchoImpl());
            service.aliasName("grpc", "example.Echo").serialize("pRoToBuF");

            // 服务显式选择优先于挂载的 JSON，启动后发布同样执行检查。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, service::register);
            assertEquals("Protobuf service must bind proto file.", failure.getMessage());
            assertTrue(context.getServiceIDs().isEmpty());
            assertTrue(context.isOnline());
        }
    }

    @Test
    public void connectorLevelProtobufSelectionAlsoRequiresAResource() throws Exception {
        // 将 Protobuf 放在连接器配置上，验证服务仍必须绑定 proto。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").option("serializeType", "pRoToBuF").protocol("grpc");
        try (MetContext context = configuration.buildContext()) {
            ConfigurationBuilder<Echo> service = context.publisher().rsfService(Echo.class, new EchoImpl());
            service.aliasName("grpc", "example.Echo");

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, service::register);
            assertEquals("Protobuf service must bind proto file.", failure.getMessage());
            assertTrue(context.getServiceIDs().isEmpty());
        }
    }

    @Test
    public void jsonMountAcceptsAnOrdinaryJavaProviderWithoutProto() throws Exception {
        // 选择 JSON 挂载，使普通 Java 服务不需要额外的 proto 资源。
        MetConfiguration configuration = new MetConfiguration();
        configuration //
                .connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("serializeType", "Json");
        try (MetContext context = configuration.buildContext()) {
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            MetBindInfo<Echo> info = publication.aliasName("grpc", "example.Echo").register();
            // 未绑定 proto 的 JSON 服务仍可完成注册并启动。
            assertNull(info.getBindResource());
            context.start();
            assertTrue(context.isOnline());
        }
    }

    @Test
    public void selectingAnotherProtocolSkipsGrpcResourceValidation() throws Exception {
        // 同时配置 gRPC 与 RSF，随后将服务限制为 RSF。
        MetConfiguration configuration = this.configuration();
        configuration.connector("tcp").protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            // 同一个实例挂载 gRPC，但该服务只选择 RSF。
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            MetBindInfo<Echo> info = publication.aliasName("grpc", "example.Echo").protocol("rsf").register();
            assertNull(info.getBindResource());
            MetBindInfo<Echo> registeredService = context.getServiceInfo(Echo.class);
            assertEquals(info.getBindID(), registeredService.getBindID());
        }
    }

    @Test
    public void grpcOnTheClasspathDoesNotValidateAnUnmountedProtocol() throws Exception {
        // 类路径中存在 gRPC 插件，但当前实例并未挂载该协议。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            MetBindInfo<Echo> info = publication.aliasName("grpc", "example.Echo").register();
            assertNull(info.getBindResource());
            MetBindInfo<Echo> registeredService = context.getServiceInfo(Echo.class);
            assertEquals(info.getBindID(), registeredService.getBindID());
        }
    }

    @Test
    public void unrelatedBindResourceDoesNotSatisfyProtobufPublication() throws Exception {
        // 为服务绑定普通对象，验证它不能充当 Protobuf 描述资源。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            ConfigurationBuilder<Echo> service = context.publisher().rsfService(Echo.class, new EchoImpl());
            service.aliasName("grpc", "example.Echo").bindResource(new Object());

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, service::register);
            assertEquals("Protobuf service must bind proto file.", failure.getMessage());
            assertTrue(context.getServiceIDs().isEmpty());
        }
    }

    @Test
    public void boundProtoAllowsProviderRegistrationAndStartup() throws Exception {
        // 加载与服务匹配的 proto，通过发布 API 绑定资源。
        MetConfiguration configuration = this.configuration();
        GrpcProtoResource proto = new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
        try (MetContext context = configuration.buildContext()) {
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            MetBindInfo<Echo> info = publication.aliasName("grpc", "example.Echo").bindResource(proto).register();
            // 发布结果保留同一个绑定资源，随后容器正常进入在线状态。
            assertSame(proto, info.getBindResource());
            context.start();
            assertTrue(context.isOnline());
        }
    }

    private MetConfiguration configuration() {
        // 共用最小 gRPC 监听配置，保留插件默认的 Protobuf 选择。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        return configuration;
    }
}
