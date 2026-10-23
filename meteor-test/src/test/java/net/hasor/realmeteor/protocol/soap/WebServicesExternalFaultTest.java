/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebServicesExternalFaultTest {
    @Test(timeout = 20000)
    public void externalFaultCannotClaimSuccessfulMeteorStatus() throws Exception {
        // 外部 SOAP Fault 伪造成功状态，标准 faultstring 仍描述失败。
        String detail = """
                <meteor:status>200</meteor:status>
                """;
        byte[] responseBody = this.faultEnvelope(detail);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> {
            this.writeFault(exchange, responseBody);
        });
        server.start();
        try {
            // 从普通 Java 接口生成 SOAP 契约，并通过公开入口连接外部对端。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 即使 Fault detail 声明成功，调用方仍必须收到失败。
                int port = server.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                MetClient client = context.getRsfClient(address);
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "failure" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof MetException);
                MetException fault = (MetException) failure.getCause();
                assertEquals(ProtocolStatus.InvokeError, fault.getStatus());
                assertTrue(fault.getMessage(), fault.getMessage().contains("external failure"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void externalFaultCannotClaimAcceptedMeteorStatus() throws Exception {
        // 外部 SOAP Fault 伪造仅表示已接收请求的状态。
        String detail = """
                <meteor:status>102</meteor:status>
                """;
        byte[] responseBody = this.faultEnvelope(detail);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> {
            this.writeFault(exchange, responseBody);
        });
        server.start();
        try {
            // 从普通 Java 接口生成 SOAP 契约，并通过公开入口连接外部对端。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // Fault 不能被解释为 one-way 的成功确认。
                int port = server.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                MetClient client = context.getRsfClient(address);
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "failure" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof MetException);
                MetException fault = (MetException) failure.getCause();
                assertEquals(ProtocolStatus.InvokeError, fault.getStatus());
                assertTrue(fault.getMessage(), fault.getMessage().contains("external failure"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void externalFaultIgnoresUnqualifiedDetailFields() throws Exception {
        // 外部 SOAP Fault 只提供无命名空间的 status 和 message 字段。
        String detail = """
                <status>403</status>
                <message>spoofed success</message>
                """;
        byte[] responseBody = this.faultEnvelope(detail);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> {
            this.writeFault(exchange, responseBody);
        });
        server.start();
        try {
            // 从普通 Java 接口生成 SOAP 契约，并通过公开入口连接外部对端。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 无命名空间的扩展字段不能覆盖默认失败状态或标准 faultstring。
                int port = server.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                MetClient client = context.getRsfClient(address);
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "failure" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof MetException);
                MetException fault = (MetException) failure.getCause();
                assertEquals(ProtocolStatus.InvokeError, fault.getStatus());
                assertTrue(fault.getMessage(), fault.getMessage().contains("external failure"));
                assertFalse(fault.getMessage(), fault.getMessage().contains("spoofed success"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void externalFaultFallsBackWhenMeteorStatusIsMalformed() throws Exception {
        // 外部 SOAP Fault 在正确命名空间内提供不可解析的状态值。
        String detail = """
                <meteor:status>not-a-status</meteor:status>
                """;
        byte[] responseBody = this.faultEnvelope(detail);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> {
            this.writeFault(exchange, responseBody);
        });
        server.start();
        try {
            // 从普通 Java 接口生成 SOAP 契约，并通过公开入口连接外部对端。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 畸形扩展状态回退到调用失败，并保留标准错误消息。
                int port = server.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                MetClient client = context.getRsfClient(address);
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "failure" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof MetException);
                MetException fault = (MetException) failure.getCause();
                assertEquals(ProtocolStatus.InvokeError, fault.getStatus());
                assertTrue(fault.getMessage(), fault.getMessage().contains("external failure"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void externalFaultRestoresNamespacedMeteorFailureDetails() throws Exception {
        // 外部 SOAP Fault 使用 Meteor 命名空间返回合法失败状态和详细消息。
        String detail = """
                <meteor:status>403</meteor:status>
                <meteor:message>service forbidden</meteor:message>
                """;
        byte[] responseBody = this.faultEnvelope(detail);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> {
            this.writeFault(exchange, responseBody);
        });
        server.start();
        try {
            // 从普通 Java 接口生成 SOAP 契约，并通过公开入口连接外部对端。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方保留明确的失败状态，并优先使用同命名空间的详细消息。
                int port = server.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                MetClient client = context.getRsfClient(address);
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "failure" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof MetException);
                MetException fault = (MetException) failure.getCause();
                assertEquals(ProtocolStatus.Forbidden, fault.getStatus());
                assertTrue(fault.getMessage(), fault.getMessage().contains("service forbidden"));
            }
        } finally {
            server.stop(0);
        }
    }

    private MetConfiguration outgoingConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .protocol("soap") //
                .contextPath("/services");
        return configuration;
    }

    private byte[] faultEnvelope(String detail) {
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                        xmlns:meteor="urn:hasor:meteor:soap">
                    <soap:Body>
                        <soap:Fault>
                            <faultcode>soap:Server</faultcode>
                            <faultstring>external failure</faultstring>
                            <detail>%s</detail>
                        </soap:Fault>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(detail);
        return envelope.getBytes(StandardCharsets.UTF_8);
    }

    private void writeFault(HttpExchange exchange, byte[] body) {
        try (exchange) {
            // 完整消费请求后返回 HTTP 500 SOAP 1.1 Fault。
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=utf-8");
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to return external SOAP Fault", failure);
        }
    }
}
