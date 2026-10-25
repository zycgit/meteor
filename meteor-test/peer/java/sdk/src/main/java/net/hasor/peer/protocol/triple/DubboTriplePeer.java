/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.triple;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSON;
import com.google.protobuf.StringValue;
import net.hasor.example.protocol.triple.interop.TripleInteropPerson;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.RpcContext;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 只在独立测试 JVM 中运行的 Apache Dubbo 3.3.6 对端。 */
public final class DubboTriplePeer {
    private DubboTriplePeer() {
    }

    public static void main(String[] args) throws Exception {
        // 对端日志允许空行，父测试必须区分空日志与进程输出真正结束。
        System.out.println();

        // 先解析独立进程参数，压缩开关在 Dubbo 初始化之前生效。
        Map<String, String> arguments = arguments(args);
        String command = arguments.getOrDefault("command", "server");
        int port = Integer.parseInt(arguments.getOrDefault("port", "0"));
        boolean gzip = Boolean.parseBoolean(arguments.getOrDefault("gzip", "false"));
        // 对外登记使用容器 DNS，绑定地址仍由 ProtocolConfig 单独控制。
        String publishedHost = System.getenv("METEOR_TEST_PEER_HOST");
        if (publishedHost != null && !publishedHost.isBlank()) {
            System.setProperty("DUBBO_IP_TO_REGISTRY", publishedHost);
        }
        System.setProperty("dubbo.application.logger", "jdk");
        System.setProperty("dubbo.rpc.tri.compressor", gzip ? "gzip" : "identity");
        System.setProperty("dubbo.metadata-service.export-port", "-1");

        // 每个隔离进程拥有自己的 Dubbo 应用，退出时关闭它创建的网络资源。
        ApplicationConfig application = new ApplicationConfig("meteor-triple-official-peer");
        application.setQosEnable(false);
        application.setMetadataType("local");
        application.setRegisterConsumer(false);
        application.setEnableFileCache(false);
        RegistryConfig registry = new RegistryConfig(RegistryConfig.NO_AVAILABLE);
        DubboBootstrap bootstrap = DubboBootstrap.getInstance().application(application).registry(registry);
        try {
            switch (command) {
                case "server":
                    server(bootstrap, port);
                    break;
                case "client":
                    client(bootstrap, arguments, port);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown peer command: " + command);
            }
        } finally {
            bootstrap.destroy();
        }
    }

    private static void server(DubboBootstrap bootstrap, int port) throws Exception {
        // 两类服务共用官方 Triple 监听端口，名称直接取独立接口的完整类名。
        int requestedPort = port;
        if (requestedPort == 0) {
            // 由操作系统分配测试端口，避免并行 JVM 同时扫描官方默认端口。
            try (ServerSocket reservation = new ServerSocket(0)) {
                requestedPort = reservation.getLocalPort();
            }
        }
        ProtocolConfig protocol = new ProtocolConfig("tri", requestedPort);
        // 官方服务向同一 Docker 网络内的独立消费者开放监听。
        protocol.setHost("0.0.0.0");
        protocol.setRegister(false);
        ServiceConfig<DubboWrappedService> wrapped = new ServiceConfig<>();
        wrapped.setInterface(DubboWrappedService.class);
        wrapped.setRef(new DubboWrappedServiceImpl());
        wrapped.setProtocol(protocol);
        wrapped.setRegister(false);
        wrapped.setSerialization("hessian2");
        wrapped.setScope("remote");
        ServiceConfig<DubboProtoService> proto = new ServiceConfig<>();
        proto.setInterface(DubboProtoService.class);
        proto.setRef(new DubboProtoServiceImpl());
        proto.setProtocol(protocol);
        proto.setRegister(false);
        proto.setScope("remote");
        bootstrap.protocol(protocol).service(wrapped).service(proto).start();

        // 父测试通过关闭 stdin 结束对端，READY 只在服务实际启动后输出。
        int boundPort = wrapped.getExportedUrls().get(0).getPort();
        System.out.println("READY " + boundPort);
        System.out.flush();
        while (System.in.read() != -1) {
            // 服务一直由父测试持有，读取到 EOF 后交给 main 统一关闭。
        }
    }

    private static void client(DubboBootstrap bootstrap, Map<String, String> arguments, int port) throws Exception {
        String scenario = arguments.getOrDefault("scenario", "echo");
        String mode = arguments.getOrDefault("mode", "wrapped");
        boolean proto = switch (mode) {
            case "proto" -> true;
            case "wrapped" -> false;
            default -> throw new IllegalArgumentException("Unknown client mode: " + mode);
        };
        String operation = scenario;
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 通过官方 ReferenceConfig 建立真实网络代理，不依赖 Meteor 的调用实现。
            Object value;
            String host = arguments.get("host");
            if (proto) {
                var reference = reference(DubboProtoService.class, host, port, operation);
                bootstrap.reference(reference).start();
                value = invokeProto(reference.get(), operation);
            } else {
                var reference = reference(DubboWrappedService.class, host, port, operation);
                bootstrap.reference(reference).start();
                value = invokeWrapped(reference.get(), operation);
            }
            result.put("value", value);
            if (proto) {
                switch (operation) {
                    case "cancel":
                        result.put("cancelled", true);
                        break;
                    default:
                        break;
                }
            }
            String metadata = RpcContext.getClientResponseContext().getAttachment("x-triple-test");
            if (metadata != null) {
                result.put("metadata", metadata);
            }
        } catch (Throwable error) {
            // 失败是独立场景的结果，保留异常类名和完整信息供父测试验证。
            result.put("errorClass", error.getClass().getName());
            result.put("error", error.getMessage());
        } finally {
            RpcContext.removeClientAttachment();
            RpcContext.removeClientResponseContext();
        }
        System.out.println("RESULT " + JSON.toJSONString(result, JSONWriter.Feature.WriteNulls));
        System.out.flush();
    }

    private static <T> ReferenceConfig<T> reference(Class<T> type, String host, int port, String scenario) {
        ReferenceConfig<T> reference = new ReferenceConfig<>();
        reference.setInterface(type);
        reference.setUrl("tri://" + host + ":" + port + "/" + type.getName() + "?serialization=hessian2");
        reference.setCheck(false);
        reference.setRetries(0);
        reference.setTimeout(switch (scenario) {
            case "timeout" -> 100;
            default -> 5000;
        });
        reference.setScope("remote");
        return reference;
    }

    private static Object invokeWrapped(DubboWrappedService service, String scenario) throws Exception {
        // 固定输入使官方对端与 Meteor 侧断言保持清晰的一对一场景对应。
        return switch (scenario) {
            case "echo" -> service.echo("Triple 中文 \uD83D\uDE42");
            case "unicode" -> service.echo("中文-\uD83D\uDE80");
            case "combine" -> service.combine("hello", 3);
            case "string-overload" -> service.overloaded("hello");
            case "int-overload" -> service.overloaded(7);
            case "no-args" -> service.noArgs();
            case "binary" -> Base64.getEncoder().encodeToString(service.binary(new byte[] { 0, 1, 2, -1 }));
            case "person" -> service.person(new TripleInteropPerson("Ada", 41));
            case "null" -> service.nullable(null);
            case "void" -> {
                service.nothing();
                yield null;
            }
            case "fault" -> service.fail("boom");
            case "metadata" -> {
                RpcContext.getClientAttachment().setAttachment("x-triple-test", "metadata");
                yield service.metadata("metadata");
            }
            case "timeout" -> service.slow("wait");
            case "concurrent" -> concurrent(service);
            default -> throw new IllegalArgumentException("Unknown wrapped scenario: " + scenario);
        };
    }

    private static Object invokeProto(DubboProtoService service, String scenario) throws Throwable {
        // 普通调用和流式调用均使用 Dubbo 自己的 Protobuf 编码与 StreamObserver。
        switch (scenario) {
            case "echo":
                return service.echo(StringValue.of("Triple 中文 \uD83D\uDE42")).getValue();
            case "unicode":
                return service.echo(StringValue.of("中文-\uD83D\uDE80")).getValue();
            case "fault":
                return service.fail(StringValue.of("boom")).getValue();
            case "metadata":
                RpcContext.getClientAttachment().setAttachment("x-triple-test", "metadata");
                return service.metadata(StringValue.of("metadata")).getValue();
            case "timeout":
                return service.slow(StringValue.of("wait")).getValue();
            case "server-stream":
                DubboResponseObserver count = new DubboResponseObserver();
                service.count(StringValue.of("3"), count);
                return count.await();
            case "client-stream":
                DubboResponseObserver collect = new DubboResponseObserver();
                StreamObserver<StringValue> input = service.collect(collect);
                input.onNext(StringValue.of("alpha"));
                input.onNext(StringValue.of("beta"));
                input.onNext(StringValue.of("gamma"));
                input.onCompleted();
                return collect.await().get(0);
            case "cancel":
                return cancel(service);
            case "bidi":
                DubboResponseObserver chat = new DubboResponseObserver();
                StreamObserver<StringValue> conversation = service.chat(chat);
                conversation.onNext(StringValue.of("alpha"));
                conversation.onNext(StringValue.of("beta"));
                conversation.onNext(StringValue.of("gamma"));
                conversation.onCompleted();
                return chat.await();
            default:
                throw new IllegalArgumentException("Unknown Protobuf scenario: " + scenario);
        }
    }

    private static List<String> concurrent(DubboWrappedService service) throws Exception {
        // 官方代理被多个调用线程共享，每次调用的请求和响应独立关联。
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<String>> calls = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                String value = "call-" + index;
                calls.add(CompletableFuture.supplyAsync(() -> service.echo(value), executor));
            }

            // 按提交顺序取回结果，让父场景能够检查每次响应的归属。
            List<String> values = new ArrayList<>();
            for (CompletableFuture<String> call : calls) {
                values.add(call.get(10, TimeUnit.SECONDS));
            }
            return values;
        } finally {
            executor.shutdownNow();
        }
    }

    private static String cancel(DubboProtoService service) throws Throwable {
        // 先收到首条响应，确认请求已经形成真实的 HTTP/2 流。
        DubboResponseObserver response = new DubboResponseObserver();
        StreamObserver<StringValue> input = service.chat(response);
        input.onNext(StringValue.of("alpha"));
        response.awaitFirst();

        // 官方本地取消发送 RST_STREAM，不向响应观察器补发终止回调。
        input.onError(new CancellationException("cancel"));

        // 取消只影响当前流，同一代理仍能建立后续调用。
        return service.echo(StringValue.of("alive")).getValue();
    }

    private static Map<String, String> arguments(String[] values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String value : values) {
            if (!value.startsWith("--")) {
                result.put("command", value);
                continue;
            }
            int split = value.indexOf('=');
            if (split < 3) {
                throw new IllegalArgumentException("Expected --name=value: " + value);
            }
            result.put(value.substring(2, split), value.substring(split + 1));
        }
        return result;
    }
}
