/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.triple.python;
import net.hasor.meteor.test.nxn.NxnRpc;
import net.hasor.example.protocol.triple.interop.TripleInteropProtoService;
import net.hasor.example.protocol.triple.interop.TripleInteropProtoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.python.PythonSdkPeerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 官方 Apache Dubbo Python beta SDK 的双向 Triple unary；不继承 Java SDK 的 POJO 范围。 */
@NxnRpc(name = "triple")
public class TriplePythonInteropTest {
    private static final String SERVICE = "net.hasor.peer.protocol.triple.DubboProtoService";

    @Test
    @Timeout(value = 45000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonDubboSdkCallsMeteorTripleProtobuf() throws Exception {
        // Meteor 仅发布 Triple 的标准 Protobuf 路径，SDK 自行完成真实 HTTP/2 调用。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0).option("httpVersion", 2) //
                .protocol("triple").contextPath("/").option("httpMode", "grpc").option("streamBody", true);
        var resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-interop.proto"));
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TripleInteropProtoService.class, new TripleInteropProtoServiceImpl()) //
                    .protocol("triple").aliasName("triple", SERVICE).serialize("Protobuf") //
                    .bindResource("triple", resource).register();

            // Python 对端使用公开 Client.unary 与 Protobuf 序列化钩子，结果由真实客户端断言。
            int port = provider.getListenAddress("triple").getPort();
            String target = ContainerProcess.runnerHost() + ":" + port;
            try (PythonSdkPeerProcess peer = PythonSdkPeerProcess.start("triple", "consumer", "--target", target)) {
                peer.requireSuccess();
            }
        }
    }

    @Test
    @Timeout(value = 45000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorTripleCallsPythonDubboSdkProtobuf() throws Exception {
        // 官方 Python SDK 用 ServiceConfig 发布真实 Triple 服务，场景只管理其专属进程。
        try (PythonSdkPeerProcess provider = PythonSdkPeerProcess.start("triple", "provider")) {
            int port = provider.awaitPort();
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
            configuration.connector("http").option("httpVersion", 2) //
                    .protocol("triple").contextPath("/").option("httpMode", "grpc").option("streamBody", true);
            var resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-interop.proto"));
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                var service = consumer.publisher().rsfService(TripleInteropProtoService.class) //
                        .protocol("triple").aliasName("triple", SERVICE).serialize("Protobuf") //
                        .bindResource("triple", resource).register();

                // Meteor 从公开远程接口发起网络调用，验证 beta SDK 的提供者返回值。
                InterAddress address = new InterAddress("triple", provider.host(), port, "default");
                TripleInteropProtoService remote = consumer.getRsfClient(address).getRemote(service);
                assertEquals("Meteor Triple 中文 🙂", remote.echo("Meteor Triple 中文 🙂"));
            }
        }
    }
}
