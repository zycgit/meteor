/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.scenario.bootstrap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.protocol.hprose.support.BootstrapTestSupport;
import net.hasor.meteor.protocol.hprose.support.example.TextService;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolExposureTest extends BootstrapTestSupport {

    @Test
    public void defaultExposureUsesBothProtocolsAndTheirOwnSerialization() throws Exception {
        // 两个接入点发布同一个服务；调用方同样配置两种协议，无全局序列化选项。
        MetConfiguration provider = new MetConfiguration();
        provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        provider.connector("http").bind("127.0.0.1", 0).protocol("hprose");
        MetConfiguration consumer = new MetConfiguration();
        consumer.connector("tcp").protocol("rsf");
        consumer.connector("http").protocol("hprose");
        try (MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            // 两端先启动，再通过公开发布入口装配服务和订阅。
            server.start();
            client.start();

            // 用服务过滤器记录请求实际采用的序列化方式。
            Map<String, String> observed = new ConcurrentHashMap<>();
            server.publisher() //
                    .bindFilter("serializer", (request, response, chain) -> {
                        String argument = (String) request.getParameterObject()[0];
                        String serializer = request.getSerializeType();
                        observed.put(argument, serializer);
                        chain.doFilter(request, response);
                    });

            // 不限制发布协议或序列化器，由两个协议各自提供默认值。
            MetBindInfo<TextService> published = server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .register();
            MetBindInfo<TextService> service = client.publisher().rsfService(TextService.class).register();
            assertTrue(published.getBindProtocols().isEmpty());
            assertNull(service.getSerializeType());

            // 强制使用目标地址，确认两次都经过真实网络且按协议补齐默认值。
            TextService tcpRemote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);
            TextService httpRemote = client.getRsfClient(server.bindAddress("hprose")).getRemote(service);
            assertEquals("tcp", tcpRemote.echo("tcp"));
            assertEquals("http", httpRemote.echo("http"));
            assertEquals("Java", observed.get("tcp"));
            assertEquals("Hprose", observed.get("http"));
        }
    }

    @Test
    public void rsfRejectsServicesPublishedOnlyOverHprose() throws Exception {
        // 发布 RSF 与 Hprose 两个入口，验证 RSF 不能调用仅供 Hprose 的服务。
        MetConfiguration provider = new MetConfiguration();
        provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        provider.connector("http").bind("127.0.0.1", 0).protocol("hprose");
        MetConfiguration consumer = new MetConfiguration();
        consumer.connector("tcp").protocol("rsf");
        consumer.connector("http").protocol("hprose");
        try (MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            // 两端先启动，再通过公开发布入口装配服务和订阅。
            server.start();
            client.start();

            // 记录实际执行次数，确认被拒绝的请求没有进入业务实现。
            AtomicInteger calls = new AtomicInteger();
            TextService implementation = value -> {
                calls.incrementAndGet();
                return value;
            };

            // 分别限定服务允许发布的协议，客户端订阅对应服务名称。
            var tcpProvider = server.publisher().rsfService(TextService.class, implementation).name("tcp");
            tcpProvider.aliasName("Hprose", "tcpEcho").protocol("RSF").register();
            var httpProvider = server.publisher().rsfService(TextService.class, implementation).name("http");
            httpProvider.aliasName("Hprose", "httpEcho").protocol("HpRoSe").register();
            var tcpConsumer = client.publisher().rsfService(TextService.class).name("tcp");
            MetBindInfo<TextService> tcp = tcpConsumer.aliasName("Hprose", "tcpEcho").register();
            var httpConsumer = client.publisher().rsfService(TextService.class).name("http");
            MetBindInfo<TextService> http = httpConsumer.aliasName("Hprose", "httpEcho").register();

            // 正常 RSF 调用先成功，再用同一客户端访问禁止的服务。
            MetClient tcpClient = client.getRsfClient(server.bindAddress("rsf"));
            Class<?>[] parameterTypes = { String.class };
            Object[] deniedArguments = { "denied" };
            assertEquals("allowed", tcpClient.getRemote(tcp).echo("allowed"));
            MetFuture deniedCall = tcpClient.asyncInvoke(http, "echo", parameterTypes, deniedArguments);
            ExecutionException denied = assertThrows(ExecutionException.class, () -> {
                deniedCall.get(5, TimeUnit.SECONDS);
            });

            // 拒绝原因明确为协议范围限制，业务只执行过允许的调用。
            MetException rejection = (MetException) denied.getCause();
            assertEquals(ProtocolStatus.Forbidden, rejection.getStatus());
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void hproseRejectsServicesPublishedOnlyOverRsf() throws Exception {
        // 发布两个协议入口，验证 Hprose 不能调用仅供 RSF 的服务。
        MetConfiguration provider = new MetConfiguration();
        provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        provider.connector("http").bind("127.0.0.1", 0).protocol("hprose");
        MetConfiguration consumer = new MetConfiguration();
        consumer.connector("tcp").protocol("rsf");
        consumer.connector("http").protocol("hprose");
        try (MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            // 两端先启动，再通过公开发布入口装配服务和订阅。
            server.start();
            client.start();

            // 记录实际执行次数，确认被拒绝的请求没有进入业务实现。
            AtomicInteger calls = new AtomicInteger();
            TextService implementation = value -> {
                calls.incrementAndGet();
                return value;
            };

            // 分别限定服务允许发布的协议，客户端订阅对应服务名称。
            var tcpProvider = server.publisher().rsfService(TextService.class, implementation).name("tcp");
            tcpProvider.aliasName("Hprose", "tcpEcho").protocol("RSF").register();
            var httpProvider = server.publisher().rsfService(TextService.class, implementation).name("http");
            httpProvider.aliasName("Hprose", "httpEcho").protocol("HpRoSe").register();
            var tcpConsumer = client.publisher().rsfService(TextService.class).name("tcp");
            MetBindInfo<TextService> tcp = tcpConsumer.aliasName("Hprose", "tcpEcho").register();
            var httpConsumer = client.publisher().rsfService(TextService.class).name("http");
            MetBindInfo<TextService> http = httpConsumer.aliasName("Hprose", "httpEcho").register();

            // 正常 Hprose 调用先成功，再验证 RSF 专属服务被拒绝。
            Class<?>[] parameterTypes = { String.class };
            Object[] deniedArguments = { "denied" };
            MetClient httpClient = client.getRsfClient(server.bindAddress("hprose"));
            assertEquals("http", httpClient.getRemote(http).echo("http"));
            MetFuture deniedHttp = httpClient.asyncInvoke(tcp, "echo", parameterTypes, deniedArguments);
            var httpFailure = assertThrows(ExecutionException.class, () -> deniedHttp.get(5, TimeUnit.SECONDS));

            // 远端返回禁止错误，拒绝的调用不增加业务执行次数。
            MetException rejection = (MetException) httpFailure.getCause();
            assertEquals(ProtocolStatus.Forbidden, rejection.getStatus());
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void disallowedMessageIsRejectedBeforeAccept() throws Exception {
        // 配置双协议应用，验证单向消息也遵循服务发布范围。
        MetConfiguration provider = new MetConfiguration();
        provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        provider.connector("http").bind("127.0.0.1", 0).protocol("hprose");
        MetConfiguration consumer = new MetConfiguration();
        consumer.connector("tcp").protocol("rsf");
        consumer.connector("http").protocol("hprose");
        try (MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            // 两端先启动，再通过公开发布入口装配服务和订阅。
            server.start();
            client.start();

            // 记录实际执行次数，确认被拒绝的请求没有进入业务实现。
            AtomicInteger calls = new AtomicInteger();
            TextService implementation = value -> {
                calls.incrementAndGet();
                return value;
            };
            Class<?>[] parameterTypes = { String.class };
            Object[] deniedArguments = { "denied" };
            MetClient tcpClient = client.getRsfClient(server.bindAddress("rsf"));
            // 单向消息也要在 Accept 前拒绝，不能把未执行误报为接收成功。
            server.publisher() //
                    .rsfService(TextService.class, implementation) //
                    .name("message") //
                    .protocol("hprose") //
                    .register();
            var messageConsumer = client.publisher().rsfService(TextService.class).name("message");
            MetBindInfo<TextService> message = messageConsumer.asMessage().register();

            // 经 RSF 发送单向消息，仍需将协议限制作为失败通知调用方。
            MetFuture deniedMessage = tcpClient.asyncInvoke(message, "echo", parameterTypes, deniedArguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                deniedMessage.get(5, TimeUnit.SECONDS);
            });
            MetException rejection = (MetException) failure.getCause();
            assertEquals(ProtocolStatus.Forbidden, rejection.getStatus());
            assertEquals(0, calls.get());
        }
    }

    @Test
    public void endpointSerializerCanBeOverriddenByAnExplicitServiceSerializer() throws Exception {
        // 将客户端协议默认序列化器设置为 JSON，供服务显式设置覆盖。
        MetConfiguration provider = new MetConfiguration();
        provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration consumer = new MetConfiguration();
        consumer.connector("tcp").protocol("rsf").option("serializeType", "jSoN");
        try (MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            // 两端先启动，再通过公开发布入口装配服务和订阅。
            server.start();
            client.start();

            // 用服务过滤器记录请求实际采用的序列化方式。
            Map<String, String> observed = new ConcurrentHashMap<>();
            server.publisher() //
                    .bindFilter("serializer", (request, response, chain) -> {
                        String argument = (String) request.getParameterObject()[0];
                        String serializer = request.getSerializeType();
                        observed.put(argument, serializer);
                        chain.doFilter(request, response);
                    });

            // 发布两个独立服务，订阅端仅为第二个服务指定 Java 序列化器。
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .name("default") //
                    .register();
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .name("override") //
                    .register();
            MetBindInfo<TextService> implicit = client.publisher() //
                    .rsfService(TextService.class) //
                    .name("default") //
                    .register();
            var override = client.publisher().rsfService(TextService.class).name("override");
            MetBindInfo<TextService> explicit = override.serialize("Java").register();

            // 同一接入点下，一个服务继承协议默认值，另一个显式选择 Java。
            MetClient caller = client.getRsfClient(server.bindAddress("rsf"));
            assertEquals("json", caller.getRemote(implicit).echo("json"));
            assertEquals("java", caller.getRemote(explicit).echo("java"));
            assertEquals("jSoN", observed.get("json"));
            assertEquals("Java", observed.get("java"));
        }
    }
}
