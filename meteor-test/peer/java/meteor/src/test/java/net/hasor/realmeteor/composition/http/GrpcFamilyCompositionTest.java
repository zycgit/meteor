/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.composition.http;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.hasor.example.composition.http.grpc.GrpcFamilyMessage;
import net.hasor.example.composition.http.grpc.GrpcFamilyService;
import net.hasor.example.composition.http.grpc.GrpcFamilyServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 同一监听上的 gRPC 家族挂载独立选择报文、上下文和服务可见范围。 */
public class GrpcFamilyCompositionTest {
    private static final String SERVICE = "meteor.composition.FamilyService";

    @Test(timeout = 30000)
    public void fourProtocolsKeepConcurrentResponsesOnTheirOwnRoutes() throws Exception {
        // 一个 HTTP 监听配置四种协议，客户端分别选择 HTTP/1 或 HTTP/2。
        MetConfiguration incoming = this.incoming();
        MetConfiguration grpcOutgoing = this.outgoing("grpc", "/grpc", 2);
        MetConfiguration webOutgoing = this.outgoing("grpc-web", "/grpc-web", 1);
        MetConfiguration connectOutgoing = this.outgoing("connect", "/connect", 1);
        MetConfiguration tripleOutgoing = this.outgoing("triple", "/triple", 2);
        GrpcProtoResource resource = this.resource();
        GrpcFamilyServiceImpl implementation = new GrpcFamilyServiceImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext grpc = grpcOutgoing.buildContext(); //
             MetContext web = webOutgoing.buildContext(); //
             MetContext connect = connectOutgoing.buildContext(); //
             MetContext triple = tripleOutgoing.buildContext()) {
            // 容器全部启动后，注解生成的同一接口挂载四种协议，消费者绑定已有文件。
            server.start();
            grpc.start();
            web.start();
            connect.start();
            triple.start();
            server.publisher().rsfService(GrpcFamilyService.class, implementation) //
                    .aliasName("grpc", SERVICE).aliasName("grpc-web", SERVICE) //
                    .aliasName("connect", SERVICE).aliasName("triple", SERVICE) //
                    .protocol("grpc", "grpc-web", "connect", "triple") //
                    .serialize("Protobuf").register();
            MetBindInfo<GrpcFamilyService> grpcBinding = grpc.publisher().rsfService(GrpcFamilyService.class) //
                    .aliasName("grpc", SERVICE).protocol("grpc") //
                    .serialize("Protobuf").bindResource("grpc", resource).register();
            MetBindInfo<GrpcFamilyService> webBinding = web.publisher().rsfService(GrpcFamilyService.class) //
                    .aliasName("grpc-web", SERVICE).protocol("grpc-web") //
                    .serialize("Protobuf").bindResource("grpc-web", resource).register();
            MetBindInfo<GrpcFamilyService> connectBinding = connect.publisher().rsfService(GrpcFamilyService.class) //
                    .aliasName("connect", SERVICE).protocol("connect") //
                    .serialize("Protobuf").bindResource("connect", resource).register();
            MetBindInfo<GrpcFamilyService> tripleBinding = triple.publisher().rsfService(GrpcFamilyService.class) //
                    .aliasName("triple", SERVICE).protocol("triple") //
                    .serialize("Protobuf").bindResource("triple", resource).register();

            // 所有地址共用真实端口，代理仍分别经过自己的协议适配。
            int port = server.getListenAddress("grpc").getPort();
            assertEquals(port, server.getListenAddress("grpc-web").getPort());
            assertEquals(port, server.getListenAddress("connect").getPort());
            assertEquals(port, server.getListenAddress("triple").getPort());
            GrpcFamilyService grpcRemote = grpc.getRsfClient(server.getListenAddress("grpc")).getRemote(grpcBinding);
            GrpcFamilyService webRemote = web.getRsfClient(server.getListenAddress("grpc-web")).getRemote(webBinding);
            GrpcFamilyService connectRemote = connect.getRsfClient(server.getListenAddress("connect")) //
                    .getRemote(connectBinding);
            GrpcFamilyService tripleRemote = triple.getRsfClient(server.getListenAddress("triple")) //
                    .getRemote(tripleBinding);
            // 负载按此顺序轮流分配给 gRPC、gRPC-Web、Connect 和 Triple。
            List<GrpcFamilyService> remotes = new ArrayList<>();
            remotes.add(grpcRemote);
            remotes.add(webRemote);
            remotes.add(connectRemote);
            remotes.add(tripleRemote);
            ExecutorService workers = Executors.newFixedThreadPool(8);
            try {
                // 并行负载为每个请求分配不同数据，检查回包不会跨协议或跨请求串用。
                List<Future<GrpcFamilyMessage>> responses = new ArrayList<>();
                for (int index = 0; index < 48; index++) {
                    GrpcFamilyService remote = remotes.get(index % remotes.size());
                    GrpcFamilyMessage request = GrpcFamilyMessage.of("request-" + index, index);
                    responses.add(workers.submit(() -> remote.echo(request)));
                }
                for (int index = 0; index < responses.size(); index++) {
                    GrpcFamilyMessage response = responses.get(index).get(5, TimeUnit.SECONDS);
                    assertEquals("family:request-" + index, response.getText());
                    assertEquals(index + 1, response.getNumber());
                }
                assertEquals(48, implementation.getCalls());
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 20000)
    public void rejectedGrpcContextDoesNotAffectTheConnectRoute() throws Exception {
        // 两个客户端在同一端口分别访问隐藏的 gRPC 上下文和可用的 Connect 上下文。
        MetConfiguration incoming = this.incoming();
        MetConfiguration grpcOutgoing = this.outgoing("grpc", "/grpc/private", 2);
        MetConfiguration connectOutgoing = this.outgoing("connect", "/connect", 1);
        GrpcProtoResource resource = this.resource();
        GrpcFamilyServiceImpl implementation = new GrpcFamilyServiceImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext grpc = grpcOutgoing.buildContext(); //
             MetContext connect = connectOutgoing.buildContext()) {
            // 已有文件发布到默认挂载；消费者通过公共注解生成相同契约。
            server.start();
            grpc.start();
            connect.start();
            server.publisher().rsfService(GrpcFamilyService.class, implementation) //
                    .aliasName("grpc", SERVICE).aliasName("grpc-web", SERVICE) //
                    .aliasName("connect", SERVICE).aliasName("triple", SERVICE) //
                    .protocol("grpc", "grpc-web", "connect", "triple") //
                    .serialize("Protobuf").bindResource("grpc", resource) //
                    .bindResource("grpc-web", resource) //
                    .bindResource("connect", resource) //
                    .bindResource("triple", resource).register();
            MetBindInfo<GrpcFamilyService> grpcBinding = grpc.publisher().rsfService(GrpcFamilyService.class) //
                    .aliasName("grpc", SERVICE).protocol("grpc") //
                    .serialize("Protobuf").register();
            MetBindInfo<GrpcFamilyService> connectBinding = connect.publisher().rsfService(GrpcFamilyService.class) //
                    .aliasName("connect", SERVICE).protocol("connect") //
                    .serialize("Protobuf").register();
            GrpcFamilyService hidden = grpc.getRsfClient(server.getListenAddress("grpc")).getRemote(grpcBinding);
            GrpcFamilyService available = connect.getRsfClient(server.getListenAddress("connect")) //
                    .getRemote(connectBinding);

            // 一个协议的拒绝结果不关闭监听，也不污染另一个协议的后续调用。
            Exception failure = assertThrows(Exception.class, () -> {
                hidden.echo(GrpcFamilyMessage.of("hidden", 1));
            });
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(12, status.getCode());
            GrpcFamilyMessage response = available.echo(GrpcFamilyMessage.of("still available", 7));
            assertEquals("family:still available", response.getText());
            assertEquals(8, response.getNumber());
            assertEquals(1, implementation.getCalls());
        }
    }

    private MetConfiguration incoming() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        ConnectorSettings endpoint = configuration.connector("http").bind("127.0.0.1", 0);
        endpoint.protocol("grpc").contextPath("/grpc").addContextPath("/grpc/private");
        endpoint.protocol("grpc-web").contextPath("/grpc-web").addContextPath("/grpc-web/private");
        endpoint.protocol("connect").contextPath("/connect").addContextPath("/connect/private");
        endpoint.protocol("triple").contextPath("/triple").addContextPath("/triple/private");
        return configuration;
    }

    private MetConfiguration outgoing(String protocol, String path, int httpVersion) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(protocol);
        configuration.connector("http").option("httpVersion", httpVersion) //
                .protocol(protocol).contextPath(path);
        return configuration;
    }

    private GrpcProtoResource resource() throws Exception {
        URL descriptor = this.getClass().getResource("/protocol/proto/composition/family.proto");
        return new GrpcProtoResource(descriptor);
    }
}
