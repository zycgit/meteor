/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.example.protocol.grpc.web.GrpcWebService;
import net.hasor.example.protocol.grpc.web.GrpcWebServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcWebHttpTest {
    @Test(timeout = 20000)
    public void binaryJsonUnaryOverHttp1() throws Exception {
        // 在 HTTP/1 上使用 Json，传输体选择 二进制 gRPC-Web 帧。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动之后，通过普通接口发布与订阅，不依赖生成的服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Json") //
                    .register();

            // 代理返回之前，必须收齐正文内的 gRPC-Web 状态尾帧。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("中文 gRPC-Web", 7));
            assertEquals("中文 gRPC-Web", response.getValue());
            assertEquals(7, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void binaryProtobufUnaryOverHttp1() throws Exception {
        // 在 HTTP/1 上使用 Protobuf，传输体选择 二进制 gRPC-Web 帧。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/web.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动之后，通过普通接口发布与订阅，不依赖生成的服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 代理返回之前，必须收齐正文内的 gRPC-Web 状态尾帧。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("中文 gRPC-Web", 7));
            assertEquals("中文 gRPC-Web", response.getValue());
            assertEquals(7, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void textJsonUnaryOverHttp1() throws Exception {
        // 在 HTTP/1 上使用 Json，传输体选择 文本 gRPC-Web 帧。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动之后，通过普通接口发布与订阅，不依赖生成的服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Json") //
                    .register();

            // 代理返回之前，必须收齐正文内的 gRPC-Web 状态尾帧。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("中文 gRPC-Web", 7));
            assertEquals("中文 gRPC-Web", response.getValue());
            assertEquals(7, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void textProtobufUnaryOverHttp1() throws Exception {
        // 在 HTTP/1 上使用 Protobuf，传输体选择 文本 gRPC-Web 帧。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, true);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/web.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动之后，通过普通接口发布与订阅，不依赖生成的服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 代理返回之前，必须收齐正文内的 gRPC-Web 状态尾帧。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("中文 gRPC-Web", 7));
            assertEquals("中文 gRPC-Web", response.getValue());
            assertEquals(7, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void binaryJsonUnaryOverHttp2() throws Exception {
        // 在 HTTP/2 上使用 Json，传输体选择 二进制 gRPC-Web 帧。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(2, false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动之后，通过普通接口发布与订阅，不依赖生成的服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Json") //
                    .register();

            // 代理返回之前，必须收齐正文内的 gRPC-Web 状态尾帧。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("中文 gRPC-Web", 7));
            assertEquals("中文 gRPC-Web", response.getValue());
            assertEquals(7, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void binaryProtobufUnaryOverHttp2() throws Exception {
        // 在 HTTP/2 上使用 Protobuf，传输体选择 二进制 gRPC-Web 帧。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(2, false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/web.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动之后，通过普通接口发布与订阅，不依赖生成的服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 代理返回之前，必须收齐正文内的 gRPC-Web 状态尾帧。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("中文 gRPC-Web", 7));
            assertEquals("中文 gRPC-Web", response.getValue());
            assertEquals(7, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void textJsonUnaryOverHttp2() throws Exception {
        // 在 HTTP/2 上使用 Json，传输体选择 文本 gRPC-Web 帧。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(2, true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动之后，通过普通接口发布与订阅，不依赖生成的服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Json") //
                    .register();

            // 代理返回之前，必须收齐正文内的 gRPC-Web 状态尾帧。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("中文 gRPC-Web", 7));
            assertEquals("中文 gRPC-Web", response.getValue());
            assertEquals(7, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void textProtobufUnaryOverHttp2() throws Exception {
        // 在 HTTP/2 上使用 Protobuf，传输体选择 文本 gRPC-Web 帧。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(2, true);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/web.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动之后，通过普通接口发布与订阅，不依赖生成的服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 代理返回之前，必须收齐正文内的 gRPC-Web 状态尾帧。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            Message response = remote.echo(Message.of("中文 gRPC-Web", 7));
            assertEquals("中文 gRPC-Web", response.getValue());
            assertEquals(7, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void binaryUnaryPreservesRepeatedRequestMetadata() throws Exception {
        // gRPC-Web 头部使用已存在的 GrpcMetadata API，服务观察真实入站值。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, false);
        AtomicReference<List<String>> observed = new AtomicReference<>();
        AtomicReference<List<byte[]>> observedBinary = new AtomicReference<>();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata metadata = GrpcMetadata.from(request);
                observed.set(metadata.getHeader("x-values"));
                observedBinary.set(metadata.getBinaryHeader("x-values-bin"));
                chain.doFilter(request, response);
            });
            client.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata metadata = GrpcMetadata.from(request);
                metadata.addHeader("x-values", "one");
                metadata.addHeader("x-values", "two");
                metadata.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 完成一次调用以后，比较协议解出的重复头部顺序和二进制内容。
            GrpcWebService remote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(service);
            assertEquals("metadata", remote.echo(Message.of("metadata", 1)).getValue());
            assertEquals(List.of("one", "two"), observed.get());
            assertArrayEquals(new byte[] { 0, -1 }, observedBinary.get().get(0));
        }
    }

    @Test(timeout = 20000)
    public void textUnaryPreservesResponseHeadersAndBodyTrailers() throws Exception {
        // 响应初始头部仍在 HTTP 头，尾部元数据由 gRPC-Web 正文末尾帧承载。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata metadata = GrpcMetadata.from(response);
                metadata.addHeader("x-head", "first");
                metadata.addTrailer("x-tail", "one");
                metadata.addTrailer("x-tail", "two");
                metadata.addBinaryTrailer("x-tail-bin", new byte[] { 1, -1 });
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 获取完整响应对象，检查文本传输没有丢失正文帧里的元数据。
            MetClient remote = client.getRsfClient(server.getListenAddress("grpc-web"));
            Class<?>[] types = { Message.class };
            Object[] arguments = { Message.of("metadata", 1) };
            MetFuture pending = remote.asyncInvoke(service, "echo", types, arguments);
            MetResponse response = pending.get(5, TimeUnit.SECONDS);
            GrpcMetadata metadata = GrpcMetadata.from(response);
            assertEquals(List.of("first"), metadata.getHeader("x-head"));
            assertEquals(List.of("one", "two"), metadata.getTrailer("x-tail"));
            assertArrayEquals(new byte[] { 1, -1 }, metadata.getBinaryTrailer("x-tail-bin").get(0));
        }
    }

    @Test(timeout = 20000)
    public void unaryErrorKeepsStatusAndEscapedDescription() throws Exception {
        // 业务拒绝只通过 gRPC-Web 状态帧报告，HTTP 本身不代表业务成功。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing(1, true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> service = client.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // Unicode、加号与百分号必须从状态帧中还原，不能被当作 Base64 或 URL 片段。
            MetClient remote = client.getRsfClient(server.getListenAddress("grpc-web"));
            Class<?>[] types = { Message.class };
            Object[] arguments = { Message.of("denied 中文 + %", 7) };
            MetFuture pending = remote.asyncInvoke(service, "fail", types, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(5, TimeUnit.SECONDS);
            });
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(7, status.getCode());
            assertEquals("denied 中文 + %", status.getDescription());
        }
    }

    @Test(timeout = 20000)
    public void grpcAndGrpcWebUseSeparateContextsOnOneHttpPort() throws Exception {
        // 原生 gRPC 与 gRPC-Web 在相同端口发布同一个接口，分别使用各自挂载点。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc-web");
        var endpoint = incoming.connector("http").bind("127.0.0.1", 0);
        endpoint.protocol("grpc").contextPath("/native").option("streamBody", true);
        endpoint.protocol("grpc-web").contextPath("/web").option("streamBody", true);
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("grpc-web");
        outgoing.connector("http").option("httpVersion", 2).protocol("grpc") //
                .contextPath("/native").option("streamBody", true);
        outgoing.connector("http").option("httpVersion", 1).protocol("grpc-web") //
                .contextPath("/web").option("streamBody", true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc", "web.Echo").aliasName("grpc-web", "web.Echo") //
                    .protocol("grpc", "grpc-web").serialize("Json").register();
            MetBindInfo<GrpcWebService> nativeService = client.publisher().rsfService(GrpcWebService.class) //
                    .name("native").aliasName("grpc", "web.Echo").protocol("grpc") //
                    .serialize("Json").register();
            MetBindInfo<GrpcWebService> webService = client.publisher().rsfService(GrpcWebService.class) //
                    .name("web").aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                    .serialize("Json").register();

            // 一次普通 HTTP 调用和一次 HTTP/2 gRPC 调用证明路由与帧格式相互隔离。
            int nativePort = server.getListenAddress("grpc").getPort();
            assertEquals(nativePort, server.getListenAddress("grpc-web").getPort());
            GrpcWebService nativeRemote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(nativeService);
            GrpcWebService webRemote = client.getRsfClient(server.getListenAddress("grpc-web")).getRemote(webService);
            assertEquals("native", nativeRemote.echo(Message.of("native", 1)).getValue());
            assertEquals("web", webRemote.echo(Message.of("web", 2)).getValue());
        }
    }

    private MetConfiguration incoming() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc-web") //
                .contextPath("/web").option("streamBody", true);
        return configuration;
    }

    private MetConfiguration outgoing(int version, boolean text) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.connector("http").option("httpVersion", version).protocol("grpc-web") //
                .contextPath("/web").option("streamBody", true).option("webText", text);
        return configuration;
    }
}
