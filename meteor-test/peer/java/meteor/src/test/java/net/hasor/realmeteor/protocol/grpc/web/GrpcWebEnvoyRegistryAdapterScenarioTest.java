/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.example.protocol.grpc.web.GrpcWebService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Meteor 自动发现真实 Envoy gRPC-Web 端点，由产品自身过滤器转发至官方 gRPC 服务。 */
@RunWith(Parameterized.class)
public class GrpcWebEnvoyRegistryAdapterScenarioTest {
    @Parameterized.Parameters(name = "grpc-web/java-envoy/adapter/{0}")
    public static Collection<Object[]> backends() {
        return List.of(new Object[] { "jdbc" }, new Object[] { "zookeeper" }, new Object[] { "nacos" });
    }

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;

    public GrpcWebEnvoyRegistryAdapterScenarioTest(String backend) {
        this.backend = backend;
    }

    @Test(timeout = 180000)
    public void meteorDiscoversBinaryEnvoyEndpointForOfficialGrpcServer() throws Exception {
        // 独立官方 SDK 服务只处理标准 gRPC，固定线契约与既有 Envoy 直连用例相同。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            var echo = GrpcWebOfficialContract.method("echo", MethodDescriptor.MethodType.UNARY);
            var handler = ServerCalls.<Message, Message>asyncUnaryCall((request, response) -> {
                response.onNext(Message.of("envoy:" + request.getValue(), request.getCount() + 1));
                response.onCompleted();
            });
            var definition = ServerServiceDefinition.builder("web.Echo").addMethod(echo, handler).build();
            Server server = NettyServerBuilder.forAddress(new InetSocketAddress(ContainerProcess.runnerHost(), 0)) //
                    .addService(definition).build().start();

            // 创建者先持有官方服务关闭动作，再启动真实 Envoy 及仅出站的 Meteor 应用。
            try (AutoCloseable serverCleanup = () -> {
                server.shutdownNow();
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IOException("Official gRPC server did not terminate");
                }
            }; EnvoyPeerProcess envoy = new EnvoyPeerProcess(server.getPort())) {
                RegistryConfiguration registry = environment.configuration();
                MetConfiguration configuration = this.configuration(registry);
                try (MetContext consumer = configuration.buildContext()) {
                    consumer.start();
                    GrpcProtoResource proto = new GrpcProtoResource( //
                            this.getClass().getResource("/protocol/proto/grpc/web.proto"));
                    MetBindInfo<GrpcWebService> service = consumer.publisher() //
                            .rsfService(GrpcWebService.class).name("envoy-echo") //
                            .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                            .serialize("Protobuf").bindResource("grpc-web", proto).register();
                    assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                    // 登记已就绪 Envoy 的实际进程和 HTTP 端点，发现适配不执行任何业务请求。
                    try (ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                        String unit = consumer.getSettings().getUnitName();
                        InterAddress address = new InterAddress("http", envoy.getHost(), envoy.getPort(), unit);
                        registrar.register(service, envoy.processId(), "cpp", "grpc-web", "http", address, "/web");
                        AddressSelectionAwaiter.await(consumer, service, "echo");

                        // 无地址参数的正式代理从自动地址本选址，Envoy 原生过滤器完成反向协议转换。
                        GrpcWebService remote = consumer.getRsfClient().getRemote(service);
                        Message result = remote.echo(Message.of("注册发现 🙂", 7));
                        assertEquals("envoy:注册发现 🙂", result.getValue());
                        assertEquals(8, result.getCount());
                    }
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.setDefaultProtocol("grpc-web");
        configuration.setApplicationName("meteor-grpc-web-consumer");
        configuration.setDataHome(this.data.newFolder("consumer").toPath());
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 注册扩展内部管理出口，不监听。
        configuration.connector("http").protocol("grpc-web").addressScheme("http") //
                .contextPath("/web").option("webText", false).option("streamBody", true);
        return configuration;
    }
}
