/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.dubbo;
import net.hasor.example.protocol.dubbo.interop.DubboInteropPerson;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.SslConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.FutureContext;
import org.apache.dubbo.rpc.service.GenericService;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 用相同业务契约启动隔离的官方 Dubbo 2.7 或 3.3 JVM。 */
public final class DubboPeer {
    private DubboPeer() {
    }

    public static void main(String[] args) throws Exception {
        // 日志空行属于正常输出，控制端不能把空日志当作进程结束。
        System.out.println();
        Map<String, String> arguments = arguments(args);
        System.setProperty("dubbo.application.logger", "jdk");
        System.setProperty("dubbo.metadata-service.export-port", "-1");
        System.setProperty("dubbo.application.enable-file-cache", "false");
        // 官方默认等待 10 秒传播服务下线通知；测试保留正式优雅退出，但把等待预算设为 1 秒。
        System.setProperty("dubbo.service.shutdown.wait", "1000");

        // 官方进程拥有自己的应用和网络资源，EOF 或单次调用结束后统一关闭。
        ApplicationConfig application = application(arguments);
        RegistryConfig registry = registry(arguments);
        DubboBootstrap bootstrap = DubboBootstrap.getInstance().application(application).registry(registry);
        if (Boolean.parseBoolean(arguments.getOrDefault("tls", "false"))) {
            bootstrap.ssl(ssl(arguments));
        }
        try {
            String command = arguments.getOrDefault("command", "server");
            switch (command) {
                case "server":
                    server(bootstrap, arguments);
                    break;
                case "client":
                    client(bootstrap, arguments);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown peer command: " + command);
            }
        } finally {
            bootstrap.destroy();
        }
    }

    private static ApplicationConfig application(Map<String, String> arguments) throws Exception {
        ApplicationConfig application = new ApplicationConfig("meteor-dubbo-official-peer");
        application.setQosEnable(false);
        application.setMetadataType("local");

        // Dubbo 3 的接口级模式需显式选择；2.7 只有接口级模式，不提供这个 setter。
        String registerMode = arguments.getOrDefault("register-mode", "interface");
        try {
            Method setter = ApplicationConfig.class.getMethod("setRegisterMode", String.class);
            setter.invoke(application, registerMode);
        } catch (NoSuchMethodException legacy) {
            switch (registerMode) {
                case "interface":
                    break;
                default:
                    throw new IllegalArgumentException("Dubbo 2.7 supports interface registration only", legacy);
            }
        }
        return application;
    }

    private static RegistryConfig registry(Map<String, String> arguments) {
        String address = arguments.getOrDefault("registry", RegistryConfig.NO_AVAILABLE);
        RegistryConfig registry = new RegistryConfig(address);
        if (arguments.containsKey("root")) {
            registry.setGroup(arguments.get("root"));
        }
        registry.setCheck(true);
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("register-mode", arguments.getOrDefault("register-mode", "interface"));
        registry.setParameters(parameters);
        return registry;
    }

    private static SslConfig ssl(Map<String, String> arguments) {
        String certificate = arguments.get("certificate");
        String privateKey = arguments.get("private-key");
        String trustCertificate = arguments.get("trust-certificate");
        SslConfig ssl = new SslConfig();
        switch (arguments.getOrDefault("command", "server")) {
            case "server":
                ssl.setServerKeyCertChainPath(certificate);
                ssl.setServerPrivateKeyPath(privateKey);
                ssl.setServerTrustCertCollectionPath(trustCertificate);
                break;
            case "client":
                // 3.3.6 的证书提供者仅在客户端 key 和 cert 均配置时开启 TLS。
                ssl.setClientKeyCertChainPath(certificate);
                ssl.setClientPrivateKeyPath(privateKey);
                ssl.setClientTrustCertCollectionPath(trustCertificate);
                break;
            default:
                throw new IllegalArgumentException("Unknown TLS peer command");
        }
        return ssl;
    }

    private static void server(DubboBootstrap bootstrap, Map<String, String> arguments) throws Exception {
        // 端口由操作系统分配，避免多个隔离 JVM 同时扫描 Dubbo 的默认端口。
        int port = Integer.parseInt(arguments.getOrDefault("port", "0"));
        if (port == 0) {
            try (ServerSocket reservation = new ServerSocket(0)) {
                port = reservation.getLocalPort();
            }
        }
        boolean registered = arguments.containsKey("registry");
        ProtocolConfig protocol = new ProtocolConfig("dubbo", port);
        protocol.setHost("127.0.0.1");
        protocol.setSerialization("hessian2");
        protocol.setRegister(registered);
        protocol.setSslEnabled(Boolean.parseBoolean(arguments.getOrDefault("tls", "false")));
        ServiceConfig<DubboInteropService> service = new ServiceConfig<>();
        service.setInterface(DubboInteropService.class);
        service.setRef(new DubboPeerService());
        service.setProtocol(protocol);
        service.setRegister(registered);
        service.setSerialization("hessian2");
        service.setScope("remote");
        service.setGroup(arguments.get("group"));
        service.setVersion(arguments.get("version"));
        bootstrap.protocol(protocol).service(service).start();

        // READY 只在正式服务完成发布之后出现，关闭 stdin 是正常退出信号。
        int boundPort = service.getExportedUrls().get(0).getPort();
        System.out.println("READY " + boundPort);
        System.out.flush();
        while (System.in.read() != -1) {
            // 持续由父测试持有，EOF 后 main 释放整个官方应用。
        }
    }

    private static void client(DubboBootstrap bootstrap, Map<String, String> arguments) throws Exception {
        String scenario = arguments.getOrDefault("scenario", "echo");
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 普通接口与泛化引用分别经过官方 ReferenceConfig，均是远程网络调用。
            Object value;
            switch (scenario) {
                case "generic":
                case "generic-person":
                case "generic-people":
                    ReferenceConfig<GenericService> generic = reference(arguments, scenario, true);
                    bootstrap.reference(generic).start();
                    value = invokeGeneric(generic.get(), scenario);
                    break;
                default:
                    ReferenceConfig<DubboInteropService> typed = reference(arguments, scenario, false);
                    bootstrap.reference(typed).start();
                    switch (scenario) {
                        case "reconnect":
                            reconnect(typed);
                            return;
                        case "timeout-isolation":
                            value = timeoutIsolation(typed.get(), result);
                            break;
                        default:
                            value = invoke(typed.get(), scenario);
                            break;
                    }
                    break;
            }
            result.put("value", value);
            String metadata = responseAttachment();
            if (metadata != null) {
                result.put("metadata", metadata);
            }
        } catch (Throwable failure) {
            // 业务失败、远程状态和连接失败保留官方原始类型及原因链供场景断言。
            recordFailure(result, failure);
        } finally {
            RpcContext.removeContext();
            RpcContext.removeServerContext();
        }
        System.out.println("RESULT " + json(result));
        System.out.flush();
    }

    private static void reconnect(ReferenceConfig<DubboInteropService> reference) throws Exception {
        if (!Version.getVersion().startsWith("3.")) {
            throw new IllegalArgumentException("Interactive application discovery requires Dubbo 3");
        }

        // 首次调用完成后保留同一原生代理，父测试随后让提供者下线并重新监听。
        DubboInteropService service = reference.get();
        String before = service.echo("before-reconnect");
        System.out.println("RESULT " + json(Map.of("value", before)));
        System.out.flush();
        BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String signal = commands.readLine();
        if (signal == null) {
            return;
        }
        int expectedPort = Integer.parseInt(signal);

        // 等待 SDK 自己的已订阅目录更新；不重建引用，也不用业务调用进行探活或重试。
        if (!(reference.getInvoker() instanceof ClusterInvoker<?> cluster)) {
            throw new IllegalStateException("Official reference does not expose its subscribed directory");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!directoryAtPort(cluster.getDirectory(), expectedPort)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Official subscribed directory did not switch to port " + expectedPort);
            }
            Thread.sleep(20);
        }

        // SDK 已明确切换目标之后仅调用一次，证明旧代理能够使用新端点。
        String after = service.echo("after-reconnect");
        System.out.println("RESULT " + json(Map.of("value", after, "port", expectedPort)));
        System.out.flush();
    }

    private static boolean directoryAtPort(Directory<?> directory, int expectedPort) {
        if (directory == null) {
            return false;
        }
        List<? extends Invoker<?>> providers = directory.getAllInvokers();
        if (providers.isEmpty()) {
            return false;
        }
        for (Invoker<?> provider : providers) {
            if (provider.getUrl().getPort() != expectedPort || !provider.isAvailable()) {
                return false;
            }
        }
        return true;
    }

    private static <T> ReferenceConfig<T> reference(Map<String, String> arguments, String scenario, boolean generic) {
        ReferenceConfig<T> reference = new ReferenceConfig<>();
        if (generic) {
            reference.setInterface(DubboInteropService.class.getName());
            reference.setGeneric("true");
        } else {
            reference.setInterface(DubboInteropService.class);
        }

        // 未配置注册中心时使用直连地址，注册中心场景完全依赖官方订阅发现。
        if (arguments.containsKey("url")) {
            reference.setUrl(arguments.get("url"));
        } else if (!arguments.containsKey("registry")) {
            String port = arguments.get("port");
            String address = "dubbo://127.0.0.1:" + port + "/" + DubboInteropService.class.getName();
            reference.setUrl(address);
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("serialization", "hessian2");
        parameters.put("ssl-enabled", arguments.getOrDefault("tls", "false"));
        boolean tls = Boolean.parseBoolean(arguments.getOrDefault("tls", "false"));
        if (tls && Version.getVersion().startsWith("3.")) {
            // 官方 3.3.6 创建无 peerHost 的 TLS 引擎，独立对端经正式 SPI 修正目标身份。
            parameters.put("client", "meteor-test-peer-host-netty");
        }
        switch (arguments.getOrDefault("register-mode", "interface")) {
            case "instance":
                // 应用级发现必须关闭接口目录回退，避免用接口注册冒充应用级互通。
                parameters.put("migration.step", "FORCE_APPLICATION");
                break;
            default:
                break;
        }
        reference.setParameters(parameters);
        reference.setCheck(false);
        reference.setRetries(0);
        reference.setScope("remote");
        reference.setGroup(arguments.get("group"));
        reference.setVersion(arguments.get("version"));
        reference.setTimeout(5000);
        switch (scenario) {
            case "timeout", "timeout-isolation":
                MethodConfig slow = new MethodConfig();
                slow.setName("slow");
                slow.setTimeout(100);
                reference.setMethods(List.of(slow));
                break;
            default:
                break;
        }
        if (scenario.startsWith("oneway")) {
            MethodConfig notice = new MethodConfig();
            notice.setName("notice");
            notice.setReturn(false);
            notice.setSent(true);
            reference.setMethods(List.of(notice));
        }
        return reference;
    }

    private static String timeoutIsolation(DubboInteropService service, Map<String, Object> result) {
        // 先用普通调用完成连接和代理预热，再单独验证 slow 方法的短期限。
        service.echo("before-timeout");
        try {
            service.slow("wait");
        } catch (Throwable failure) {
            recordFailure(result, failure);
        }

        // 超时后保留同一个代理，后续调用成功证明响应关联没有被污染。
        String recovered = service.echo("after-timeout");
        result.put("recovered", recovered);
        return recovered;
    }

    private static void recordFailure(Map<String, Object> result, Throwable failure) {
        result.put("errorClass", failure.getClass().getName());
        result.put("error", failure.getMessage());
        List<String> causes = new ArrayList<>();
        Throwable cause = failure.getCause();
        while (cause != null && causes.size() < 8) {
            causes.add(cause.getClass().getName() + ": " + cause.getMessage());
            cause = cause.getCause();
        }
        result.put("causes", causes);
    }

    static void responseAttachment(String value) {
        // getServerContext 的返回类型在 2.7 与 3.3 间发生二进制变化，隔离对端适配它。
        Object context = serverContext();
        try {
            Method setter = context.getClass().getMethod("setAttachment", String.class, String.class);
            setter.invoke(context, "x-dubbo-test", value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot write official response attachment", failure);
        }
    }

    private static String responseAttachment() throws Exception {
        Object context = serverContext();
        String metadata;
        try {
            Method getter = context.getClass().getMethod("getAttachment", String.class);
            metadata = (String) getter.invoke(context, "x-dubbo-test");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read official response attachment", failure);
        }

        // 2.7 的单 URL 直连不经过 ClusterInterceptor，响应附加项保留在公开结果 Future 中。
        if (metadata == null && Version.getVersion().startsWith("2.")) {
            CompletableFuture<AppResponse> future = FutureContext.getContext().getCompatibleCompletableFuture();
            if (future != null) {
                AppResponse response = future.get(5, TimeUnit.SECONDS);
                metadata = response.getAttachment("x-dubbo-test");
            }
        }
        return metadata;
    }

    private static Object serverContext() {
        try {
            return RpcContext.class.getMethod("getServerContext").invoke(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot access official response context", failure);
        }
    }

    private static Object invoke(DubboInteropService service, String scenario) throws Exception {
        return switch (scenario) {
            case "echo" -> service.echo("Dubbo 中文 \uD83D\uDE42");
            case "unicode" -> service.echo("中文-\uD83D\uDE80");
            case "combine" -> service.combine("hello", 3);
            case "string-overload" -> service.overloaded("hello");
            case "int-overload" -> service.overloaded(7);
            case "no-args" -> service.noArgs();
            case "binary" -> Base64.getEncoder().encodeToString(service.binary(new byte[] { 0, 1, 2, -1 }));
            case "person" -> service.person(new DubboInteropPerson("Ada", 41));
            case "people" -> service.people(List.of(new DubboInteropPerson("Ada", 41)));
            case "properties" -> service.properties(Map.of("name", "Ada", "age", 41));
            case "null" -> service.nullable(null);
            case "void" -> {
                service.nothing();
                yield null;
            }
            case "fault" -> service.fail("boom");
            case "metadata" -> {
                RpcContext.getContext().setAttachment("x-dubbo-test", "metadata");
                yield service.metadata("metadata");
            }
            case "timeout" -> service.slow("wait");
            case "concurrent" -> concurrent(service);
            case "oneway" -> {
                service.notice("notice");
                yield null;
            }
            case "oneway-observed" -> {
                service.notice("notice");
                yield service.notices();
            }
            default -> throw new IllegalArgumentException("Unknown peer scenario: " + scenario);
        };
    }

    private static Object invokeGeneric(GenericService service, String scenario) {
        switch (scenario) {
            case "generic":
                String[] combineTypes = { "java.lang.String", "int" };
                Object[] combineArguments = { "hello", 3 };
                return service.$invoke("combine", combineTypes, combineArguments);
            case "generic-person":
                Map<String, Object> person = new LinkedHashMap<>();
                person.put("class", DubboInteropPerson.class.getName());
                person.put("name", "Ada");
                person.put("age", 41);
                String[] types = { DubboInteropPerson.class.getName() };
                return service.$invoke("person", types, new Object[] { person });
            case "generic-people":
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("class", DubboInteropPerson.class.getName());
                item.put("name", "Ada");
                item.put("age", 41);
                Object[] arguments = { List.of(item) };
                return service.$invoke("people", new String[] { "java.util.List" }, arguments);
            default:
                throw new IllegalArgumentException("Unknown generic peer scenario: " + scenario);
        }
    }

    private static List<String> concurrent(DubboInteropService service) throws Exception {
        // 多个调用共用官方代理，验证请求 ID 与响应关联在真实并发下保持隔离。
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String value = "call-" + i;
                tasks.add(() -> service.echo(value));
            }
            List<Future<String>> futures = workers.invokeAll(tasks, 10, TimeUnit.SECONDS);
            List<String> values = new ArrayList<>();
            for (Future<String> future : futures) {
                values.add(future.get(1, TimeUnit.SECONDS));
            }
            return values;
        } finally {
            workers.shutdownNow();
            if (!workers.awaitTermination(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Official peer workers did not stop");
            }
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static String json(Object value) throws Exception {
        // 两个 SDK 的 JSON 依赖隔离，不把 Meteor 的重定位 Jackson 带进官方进程。
        Class<?> jsonType;
        Class<? extends Enum> featureType;
        String featureName;
        try {
            jsonType = Class.forName("com.alibaba.fastjson2.JSON");
            featureType = (Class<? extends Enum>) Class.forName("com.alibaba.fastjson2.JSONWriter$Feature");
            featureName = "WriteNulls";
        } catch (ClassNotFoundException legacy) {
            jsonType = Class.forName("com.alibaba.fastjson.JSON");
            featureType = (Class<? extends Enum>) Class.forName("com.alibaba.fastjson.serializer.SerializerFeature");
            featureName = "WriteMapNullValue";
        }
        Object features = Array.newInstance(featureType, 1);
        Array.set(features, 0, Enum.valueOf(featureType, featureName));
        Method serialize = jsonType.getMethod("toJSONString", Object.class, features.getClass());
        return (String) serialize.invoke(null, value, features);
    }

    private static Map<String, String> arguments(String[] arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments) {
            int separator = argument.indexOf('=');
            if (!argument.startsWith("--") || separator < 3) {
                throw new IllegalArgumentException("Peer argument must use --name=value: " + argument);
            }
            values.put(argument.substring(2, separator), argument.substring(separator + 1));
        }
        return values;
    }
}
