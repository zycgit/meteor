/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.python;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.hasor.example.contract.types.TypeEchoService;
import net.hasor.example.contract.types.TypeEchoServiceImpl;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.example.contract.types.TypeValueVectors;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.python.PythonTypePeerProcess;
import net.hasor.realmeteor.matrix.types.TypeValueAssertions;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** 官方 Python gRPC SDK 与 Meteor 的逐类型双向互通，整数保留真实 64 位表示。 */
@RunWith(Parameterized.class)
public class PythonGrpcTypeInteropTest {
    private static final String SERVICE = "meteor.types.TypeEchoService";
    private final        String vector;

    public PythonGrpcTypeInteropTest(String type, String vector) {
        this.vector = vector;
    }

    @Parameterized.Parameters(name = "types/python/grpc-protobuf/direct/{0}/{1}")
    public static Collection<Object[]> vectors() throws Exception {
        // 每个类型向量形成独立 JUnit 实例，提供者和消费者由两个测试方法分别执行。
        List<Object[]> parameters = new ArrayList<>();
        for (String vector : TypeValueVectors.names()) {
            parameters.add(new Object[] { TypeValueVectors.type(vector), vector });
        }
        return parameters;
    }

    @Test(timeout = 45000)
    public void meteorProviderPreservesPythonNativeType() throws Exception {
        // Meteor 从 bootstrap 启动，只发布共享普通接口，并绑定 SDK 使用的相同 Protobuf 契约。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TypeEchoService.class, new TypeEchoServiceImpl()) //
                    .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                    .serialize("Protobuf").bindResource("grpc", this.resource()).register();

            // 独立 Python 容器构造真实 typed message，逐项核对当前类型与业务收据。
            InterAddress address = provider.getListenAddress("grpc");
            try (PythonTypePeerProcess peer = PythonTypePeerProcess.consumer( //
                    "grpc", "protobuf", this.vector, address.getHost(), address.getPort())) {
                peer.awaitSuccess(this.vector);
            }
        }
    }

    @Test(timeout = 45000)
    public void meteorConsumerPreservesPythonNativeType() throws Exception {
        // 官方 Python server 独占网络和生成契约；Meteor 只配置出站协议后从公开入口订阅。
        MetConfiguration configuration = this.configuration(false);
        try (PythonTypePeerProcess peer = PythonTypePeerProcess.provider("grpc", "protobuf", this.vector); //
             MetContext consumer = configuration.buildContext()) {
            int port = peer.awaitPort();
            consumer.start();
            MetBindInfo<TypeEchoService> service = consumer.publisher().rsfService(TypeEchoService.class) //
                    .name(SERVICE).protocol("grpc").aliasName("grpc", SERVICE) //
                    .serialize("Protobuf").bindResource("grpc", this.resource()).register();

            // 参数和响应直接使用声明类型 DTO，断言只对应当前向量，不经 JSON 字符串回显。
            InterAddress address = new InterAddress("grpc", peer.host(), port, "default");
            TypeEchoService remote = consumer.getRsfClient(address).getRemote(service);
            TypeValue expected = TypeValueVectors.value(this.vector);
            TypeValue actual = remote.echo(expected);
            TypeValueAssertions.assertVector(this.vector, expected, actual);
        }
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/protocol/proto/types/types.proto"));
    }

    private MetConfiguration configuration(boolean listen) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.setDefaultTimeout(10000).setQueueMinPoolSize(2).setQueueMaxPoolSize(4);
        MetConfiguration.ConnectorSettings http = configuration.connector("http").option("httpVersion", 2);
        if (listen) {
            http.bind(ContainerProcess.runnerHost(), 0);
        }
        http.protocol("grpc").contextPath("/");
        return configuration;
    }
}
