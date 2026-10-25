/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix.types;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
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
import net.hasor.realmeteor.environment.golang.TypePeerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertTrue;

/** Go 原生宽度和 Protobuf/Connect 两种正式 SDK 的逐类型双向互通。 */
@RunWith(Parameterized.class)
public class GoProtoTypeMatrixTest {
    private static final String SERVICE = "meteor.types.TypeEchoService";
    private final        String protocol;
    private final        String type;
    private final        String vector;

    public GoProtoTypeMatrixTest(String protocol, String type, String vector) {
        this.protocol = protocol;
        this.type = type;
        this.vector = vector;
    }

    @Parameterized.Parameters(name = "types/Go/{0}/direct/{1}/{2}")
    public static Collection<Object[]> configurations() throws IOException {
        // 协议顺序固定为 gRPC、Connect；每一份边界向量独占一条 JUnit 结果。
        List<String> protocols = new ArrayList<>();
        protocols.add("grpc");
        protocols.add("connect");
        List<Object[]> cases = new ArrayList<>();
        for (String protocol : protocols) {
            for (String vector : TypeValueVectors.names()) {
                cases.add(new Object[] { protocol, TypeValueVectors.type(vector), vector });
            }
        }
        return cases;
    }

    @Test(timeout = 40000)
    public void providerAcceptsOneNativeGoTypeVector() throws Exception {
        // Meteor 经公开 bootstrap 启动后发布普通 Java 接口；文件声明真实的线上字段类型。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TypeEchoService.class, new TypeEchoServiceImpl()) //
                    .aliasName(this.protocol, SERVICE).protocol(this.protocol).serialize("Protobuf") //
                    .bindResource(this.protocol, this.resource()).register();
            InterAddress address = provider.getListenAddress(this.protocol);
            String target = address.getHostPort();
            if (StringUtils.equals(this.protocol, "connect")) {
                target = "http://" + target;
            }

            // 独立 Go 容器恢复 int64、uint64 和浮点原值，官方 SDK 完成真实 RPC。
            try (TypePeerProcess peer = TypePeerProcess.start(this.protocol, "client", this.vector, target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(this.type + ": " + result, result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 40000)
    public void consumerReceivesOneNativeGoTypeVector() throws Exception {
        // Go SDK 服务选择临时端口；Meteor 只配置出站能力，不能走本地服务调用。
        MetConfiguration configuration = this.configuration(false);
        try (TypePeerProcess peer = TypePeerProcess.start(this.protocol, "server", this.vector, null); //
             MetContext consumer = configuration.buildContext()) {
            String ready = peer.awaitReady();
            URI endpoint = URI.create(ready.contains("://") ? ready : "http://" + ready);
            consumer.start();
            MetBindInfo<TypeEchoService> subscription = consumer.publisher().rsfService(TypeEchoService.class) //
                    .aliasName(this.protocol, SERVICE).protocol(this.protocol).serialize("Protobuf") //
                    .bindResource(this.protocol, this.resource()).register();
            InterAddress address = new InterAddress(this.protocol, endpoint.getHost(), endpoint.getPort(), "default");
            TypeEchoService remote = consumer.getRsfClient(address).getRemote(subscription);

            // 返回值必须保留当前向量的确切类型值，并带有 Go 提供者新生成的业务回执。
            TypeValue request = TypeValueVectors.value(this.vector);
            TypeValue actual = remote.echo(request);
            TypeValueAssertions.assertVector(this.vector, request, actual);
        }
    }

    private MetConfiguration configuration(boolean provider) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(this.protocol);
        configuration.setDefaultTimeout(10000);
        int httpVersion = StringUtils.equals(this.protocol, "grpc") ? 2 : 1;
        var connector = configuration.connector("http").option("httpVersion", httpVersion);
        if (provider) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        connector.protocol(this.protocol).contextPath("/");
        return configuration;
    }

    private GrpcProtoResource resource() throws IOException {
        return new GrpcProtoResource(this.getClass().getResource("/vectors/types/types.proto"));
    }
}
