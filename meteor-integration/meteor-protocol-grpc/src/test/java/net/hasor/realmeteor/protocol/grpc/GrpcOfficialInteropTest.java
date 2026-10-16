/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.grpc.testing.integration.TestServiceClient;
import net.hasor.example.protocol.grpc.InteropService;
import net.hasor.example.protocol.grpc.InteropServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFilter;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Test;

/** 原样运行官方 gRPC Java interop 客户端；服务仍从 Meteor bootstrap 发布普通接口。 */
public class GrpcOfficialInteropTest {
    @Test(timeout = 40000)
    public void officialEmptyUnary() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证空消息的一元调用，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=empty_unary", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialLargeUnary() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证大消息的一元调用，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=large_unary", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialClientStreaming() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证客户端连续发送请求消息，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=client_streaming", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialServerStreaming() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证服务端连续返回响应消息，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=server_streaming", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialPingPong() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证双向流逐条交替收发，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=ping_pong", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialEmptyStream() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证未发送消息就结束双向流，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=empty_stream", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialCustomMetadata() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证初始和末尾元数据的回送，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=custom_metadata", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialStatusCodeAndMessage() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证指定状态码与错误消息的传递，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=status_code_and_message", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialSpecialStatusMessage() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证包含特殊字符的错误消息，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=special_status_message", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialUnimplementedMethod() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证调用不存在的方法，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=unimplemented_method", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialUnimplementedService() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证调用不存在的服务，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=unimplemented_service", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialCancelAfterBegin() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证发起调用后立即取消，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=cancel_after_begin", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialCancelAfterFirstResponse() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证收到首条响应后取消，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=cancel_after_first_response", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialTimeoutOnSleepingServer() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证服务尚未返回时调用超时，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=timeout_on_sleeping_server", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialClientCompressedUnaryNoprobe() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证带压缩请求的一元调用，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=client_compressed_unary_noprobe", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialClientCompressedStreamingNoprobe() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证带压缩请求的客户端流，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=client_compressed_streaming_noprobe", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialRpcSoak() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证同一通道上的连续调用，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=rpc_soak", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 40000)
    public void officialChannelSoak() throws Exception {
        // 配置 HTTP/2 流式接入，并加载官方互通服务使用的 proto。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(15000);
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("grpc") //
                .option("streamBody", true);
        URL protoFile = this.getClass().getResource("/interop/interop-test.proto");
        GrpcProtoResource proto = new GrpcProtoResource(protoFile);
        ExecutorService delivery = Executors.newCachedThreadPool();
        try (MetContext context = configuration.buildContext()) {
            // 启动容器后，安装官方测试协议要求的元数据回送过滤器。
            context.start();
            MetFilter echoMetadata = (request, response, chain) -> {
                GrpcMetadata input = GrpcMetadata.from(request);
                GrpcMetadata output = GrpcMetadata.from(response);
                for (String value : input.getHeader("x-grpc-test-echo-initial")) {
                    output.addHeader("x-grpc-test-echo-initial", value);
                }
                for (byte[] value : input.getBinaryHeader("x-grpc-test-echo-trailing-bin")) {
                    output.addBinaryTrailer("x-grpc-test-echo-trailing-bin", value);
                }
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("interop-echo-metadata", echoMetadata);

            // 发布普通 Java 接口，绑定官方服务名与 Protobuf 定义。
            InteropServiceImpl implementation = new InteropServiceImpl(delivery);
            context.publisher().rsfService(InteropService.class, implementation) //
                    .aliasName("grpc", "grpc.testing.TestService") //
                    .serialize("Protobuf") //
                    .bindResource(proto) //
                    .register();

            // 由官方客户端验证反复创建通道并调用，保留其原有请求大小与断言。
            String port = Integer.toString(context.bindAddress("grpc").getPort());
            String[] arguments = { //
                    "--server_host=127.0.0.1", //
                    "--server_port=" + port, //
                    "--use_tls=false", //
                    "--test_case=channel_soak", //
                    "--soak_iterations=5", //
                    "--soak_per_iteration_max_acceptable_latency_ms=5000", //
                    "--soak_overall_timeout_seconds=30" //
            };
            TestServiceClient.main(arguments);
        } finally {
            // 等待业务消息投递线程退出，避免后续场景复用残留任务。
            delivery.shutdownNow();
            delivery.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

}
