/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
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
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolExposureTest extends BootstrapTestSupport {
    public interface Echo {
        String echo(String value);
    }

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
            Map<String, String> observed = new ConcurrentHashMap<>();
            server.publisher().bindFilter("serializer", (request, response, chain) -> {
                observed.put((String) request.getParameterObject()[0], request.getSerializeType());
                chain.doFilter(request, response);
            });
            MetBindInfo<Echo> published = server.publisher().rsfService(Echo.class, value -> value).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            assertTrue(published.getBindProtocols().isEmpty());
            assertNull(service.getSerializeType());
            server.start();
            client.start();

            // 强制使用目标地址，确认两次都经过真实网络且按协议补齐默认值。
            Echo tcpRemote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);
            Echo httpRemote = client.getRsfClient(server.bindAddress("hprose")).getRemote(service);
            assertEquals("tcp", tcpRemote.echo("tcp"));
            assertEquals("http", httpRemote.echo("http"));
            assertEquals("Java", observed.get("tcp"));
            assertEquals("Hprose", observed.get("http"));
        }
    }

    @Test
    public void selectedProtocolsRejectOtherIngressIncludingMessageCalls() throws Exception {
        MetConfiguration provider = new MetConfiguration();
        provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        provider.connector("http").bind("127.0.0.1", 0).protocol("hprose");
        MetConfiguration consumer = new MetConfiguration();
        consumer.connector("tcp").protocol("rsf");
        consumer.connector("http").protocol("hprose");
        try (MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            AtomicInteger calls = new AtomicInteger();
            Echo implementation = value -> {
                calls.incrementAndGet();
                return value;
            };
            var tcpProvider = server.publisher().rsfService(Echo.class, implementation).name("tcp");
            tcpProvider.aliasName("Hprose", "tcpEcho").protocol("RSF").register();
            var httpProvider = server.publisher().rsfService(Echo.class, implementation).name("http");
            httpProvider.aliasName("Hprose", "httpEcho").protocol("HpRoSe").register();
            var tcpConsumer = client.publisher().rsfService(Echo.class).name("tcp");
            MetBindInfo<Echo> tcp = tcpConsumer.aliasName("Hprose", "tcpEcho").register();
            var httpConsumer = client.publisher().rsfService(Echo.class).name("http");
            MetBindInfo<Echo> http = httpConsumer.aliasName("Hprose", "httpEcho").register();
            server.start();
            client.start();

            // 普通请求只能进入服务声明的协议接入点。
            MetClient tcpClient = client.getRsfClient(server.bindAddress("rsf"));
            Class<?>[] parameterTypes = { String.class };
            Object[] deniedArguments = { "denied" };
            assertEquals("allowed", tcpClient.getRemote(tcp).echo("allowed"));
            MetFuture deniedCall = tcpClient.asyncInvoke(http, "echo", parameterTypes, deniedArguments);
            ExecutionException denied = assertThrows(ExecutionException.class, () -> {
                deniedCall.get(5, TimeUnit.SECONDS);
            });
            assertEquals(ProtocolStatus.Forbidden, ((MetException) denied.getCause()).getStatus());
            assertEquals(1, calls.get());

            // HTTP 入站同样执行暴露限制，允许本协议服务并拒绝 TCP 专属服务。
            MetClient httpClient = client.getRsfClient(server.bindAddress("hprose"));
            assertEquals("http", httpClient.getRemote(http).echo("http"));
            MetFuture deniedHttp = httpClient.asyncInvoke(tcp, "echo", parameterTypes, deniedArguments);
            var httpFailure = assertThrows(ExecutionException.class, () -> deniedHttp.get(5, TimeUnit.SECONDS));
            assertEquals(ProtocolStatus.Forbidden, ((MetException) httpFailure.getCause()).getStatus());
            assertEquals(2, calls.get());

            // 单向消息也要在 Accept 前拒绝，不能把未执行误报为接收成功。
            server.publisher().rsfService(Echo.class, implementation).name("message").protocol("hprose").register();
            var messageConsumer = client.publisher().rsfService(Echo.class).name("message");
            MetBindInfo<Echo> message = messageConsumer.asMessage().register();
            MetFuture deniedMessage = tcpClient.asyncInvoke(message, "echo", parameterTypes, deniedArguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                deniedMessage.get(5, TimeUnit.SECONDS);
            });
            assertEquals(ProtocolStatus.Forbidden, ((MetException) failure.getCause()).getStatus());
            assertEquals(2, calls.get());
        }
    }

    @Test
    public void endpointSerializerCanBeOverriddenByAnExplicitServiceSerializer() throws Exception {
        MetConfiguration provider = new MetConfiguration();
        provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration consumer = new MetConfiguration();
        consumer.connector("tcp").protocol("rsf").option("serializeType", "jSoN");
        try (MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            Map<String, String> observed = new ConcurrentHashMap<>();
            server.publisher().bindFilter("serializer", (request, response, chain) -> {
                observed.put((String) request.getParameterObject()[0], request.getSerializeType());
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(Echo.class, value -> value).name("default").register();
            server.publisher().rsfService(Echo.class, value -> value).name("override").register();
            MetBindInfo<Echo> implicit = client.publisher().rsfService(Echo.class).name("default").register();
            var override = client.publisher().rsfService(Echo.class).name("override");
            MetBindInfo<Echo> explicit = override.serialize("Java").register();
            server.start();
            client.start();

            // 同一接入点下，一个服务继承协议默认值，另一个显式选择 Java。
            MetClient caller = client.getRsfClient(server.bindAddress("rsf"));
            assertEquals("json", caller.getRemote(implicit).echo("json"));
            assertEquals("java", caller.getRemote(explicit).echo("java"));
            assertEquals("jSoN", observed.get("json"));
            assertEquals("Java", observed.get("java"));
        }
    }
}
