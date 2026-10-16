/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.protocol.soap.support.EchoService;
import net.hasor.meteor.protocol.soap.support.SignalService;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebServicesSoap12ResponseTest {
    private static final String NAMESPACE = "http://www.w3.org/2003/05/soap-envelope";

    @Test(timeout = 20000)
    public void oneWayAcceptsEmptyHttp200() throws Exception {
        // 外部 HTTP 对端接收 SOAP 1.2 请求并返回空 200。
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> soapAction = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/SignalService", exchange -> {
            try (exchange) {
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                soapAction.set(exchange.getRequestHeaders().getFirst("SOAPAction"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        try {
            // 通过公开配置订阅带消息注解的 Java 接口。
            MetConfiguration configuration = this.configuration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<SignalService> service = context.publisher() //
                        .rsfService(SignalService.class) //
                        .aliasName("soap", "SignalService") //
                        .protocol("soap") //
                        .register();

                // 调用完成说明空成功响应已被当作 one-way 回执。
                SignalService remote = context.getRsfClient(this.address(server)).getRemote(service);
                remote.signal("notice");
                assertTrue(contentType.get(), contentType.get().startsWith("application/soap+xml"));
                String action = "action=\"urn:hasor:meteor:soap:SignalService/signal\"";
                assertTrue(contentType.get(), contentType.get().contains(action));
                assertNull(soapAction.get());
                assertTrue(body.get(), body.get().contains(NAMESPACE));
                assertTrue(body.get(), body.get().contains("notice"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void oneWayAcceptsEmptyHttp202() throws Exception {
        // 外部 HTTP 对端接收 SOAP 1.2 请求并返回空 202。
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> soapAction = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/SignalService", exchange -> {
            try (exchange) {
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                soapAction.set(exchange.getRequestHeaders().getFirst("SOAPAction"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(202, -1);
            }
        });
        server.start();
        try {
            // 通过公开配置订阅带消息注解的 Java 接口。
            MetConfiguration configuration = this.configuration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<SignalService> service = context.publisher() //
                        .rsfService(SignalService.class) //
                        .aliasName("soap", "SignalService") //
                        .protocol("soap") //
                        .register();

                // 调用完成说明空成功响应已被当作 one-way 回执。
                SignalService remote = context.getRsfClient(this.address(server)).getRemote(service);
                remote.signal("notice");
                assertTrue(contentType.get(), contentType.get().startsWith("application/soap+xml"));
                String action = "action=\"urn:hasor:meteor:soap:SignalService/signal\"";
                assertTrue(contentType.get(), contentType.get().contains(action));
                assertNull(soapAction.get());
                assertTrue(body.get(), body.get().contains(NAMESPACE));
                assertTrue(body.get(), body.get().contains("notice"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void oneWayAcceptsEmptyHttp204() throws Exception {
        // 外部 HTTP 对端接收 SOAP 1.2 请求并返回空 204。
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> soapAction = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/SignalService", exchange -> {
            try (exchange) {
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                soapAction.set(exchange.getRequestHeaders().getFirst("SOAPAction"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        try {
            // 通过公开配置订阅带消息注解的 Java 接口。
            MetConfiguration configuration = this.configuration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<SignalService> service = context.publisher() //
                        .rsfService(SignalService.class) //
                        .aliasName("soap", "SignalService") //
                        .protocol("soap") //
                        .register();

                // 调用完成说明空成功响应已被当作 one-way 回执。
                SignalService remote = context.getRsfClient(this.address(server)).getRemote(service);
                remote.signal("notice");
                assertTrue(contentType.get(), contentType.get().startsWith("application/soap+xml"));
                String action = "action=\"urn:hasor:meteor:soap:SignalService/signal\"";
                assertTrue(contentType.get(), contentType.get().contains(action));
                assertNull(soapAction.get());
                assertTrue(body.get(), body.get().contains(NAMESPACE));
                assertTrue(body.get(), body.get().contains("notice"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void externalReceiverFaultPreservesReason() throws Exception {
        // 合法 Receiver Fault 即使伪造成功扩展状态也应失败。
        String fault = """
                <soap:Code><soap:Value>soap:Receiver</soap:Value></soap:Code>
                <soap:Reason><soap:Text xml:lang="en">external failure</soap:Text></soap:Reason>
                <soap:Detail><m:status xmlns:m="urn:hasor:meteor:soap">200</m:status></soap:Detail>
                """;
        byte[] responseBody = this.faultEnvelope(fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.writeFault(exchange, responseBody));
        server.start();
        try {
            // Meteor 通过公开 Bootstrap 订阅服务，连接真实 HTTP 故障对端。
            MetConfiguration configuration = this.configuration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方应立即收到相应协议失败，而不是空成功或超时。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "failure" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof MetException);
                String message = failure.getCause().getMessage();
                assertTrue(message, message.contains("external failure"));
                assertEquals(ProtocolStatus.InvokeError, ((MetException) failure.getCause()).getStatus());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void faultWithoutCodeIsRejected() throws Exception {
        // SOAP 1.2 Fault 缺少必需的 Code。
        String fault = """
                <soap:Reason><soap:Text xml:lang="en">external failure</soap:Text></soap:Reason>
                """;
        byte[] responseBody = this.faultEnvelope(fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.writeFault(exchange, responseBody));
        server.start();
        try {
            // Meteor 通过公开 Bootstrap 订阅服务，连接真实 HTTP 故障对端。
            MetConfiguration configuration = this.configuration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方应立即收到相应协议失败，而不是空成功或超时。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "failure" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IllegalArgumentException);
                String message = failure.getCause().getMessage();
                assertTrue(message, message.contains("requires Code/Value and Reason"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void faultWithoutReasonIsRejected() throws Exception {
        // SOAP 1.2 Fault 缺少必需的 Reason。
        String fault = """
                <soap:Code><soap:Value>soap:Receiver</soap:Value></soap:Code>
                """;
        byte[] responseBody = this.faultEnvelope(fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.writeFault(exchange, responseBody));
        server.start();
        try {
            // Meteor 通过公开 Bootstrap 订阅服务，连接真实 HTTP 故障对端。
            MetConfiguration configuration = this.configuration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方应立即收到相应协议失败，而不是空成功或超时。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "failure" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IllegalArgumentException);
                String message = failure.getCause().getMessage();
                assertTrue(message, message.contains("requires Code/Value and Reason"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void faultReasonWithoutLanguageIsRejected() throws Exception {
        // SOAP 1.2 Reason Text 缺少 xml:lang。
        String fault = """
                <soap:Code><soap:Value>soap:Receiver</soap:Value></soap:Code>
                <soap:Reason><soap:Text>external failure</soap:Text></soap:Reason>
                """;
        byte[] responseBody = this.faultEnvelope(fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.writeFault(exchange, responseBody));
        server.start();
        try {
            // Meteor 通过公开 Bootstrap 订阅服务，连接真实 HTTP 故障对端。
            MetConfiguration configuration = this.configuration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方应立即收到相应协议失败，而不是空成功或超时。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "failure" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IllegalArgumentException);
                String message = failure.getCause().getMessage();
                assertTrue(message, message.contains("requires xml:lang"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void faultWithForeignTopLevelCodeIsRejected() throws Exception {
        // 应用命名空间的顶层 Fault Code 不能替代标准 SOAP Code。
        String fault = """
                <soap:Code><soap:Value xmlns:m="urn:test">m:Receiver</soap:Value></soap:Code>
                <soap:Reason><soap:Text xml:lang="en">external failure</soap:Text></soap:Reason>
                """;
        byte[] responseBody = this.faultEnvelope(fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.writeFault(exchange, responseBody));
        server.start();
        try {
            // Meteor 通过公开 Bootstrap 订阅服务，连接真实 HTTP 故障对端。
            MetConfiguration configuration = this.configuration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方应立即收到相应协议失败，而不是空成功或超时。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "failure" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IllegalArgumentException);
                String message = failure.getCause().getMessage();
                assertTrue(message, message.contains("Invalid SOAP 1.2 Fault Code"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void faultWithUnboundCodePrefixIsRejected() throws Exception {
        // Fault Code 的 QName 前缀必须有命名空间绑定。
        String fault = """
                <soap:Code><soap:Value>unbound:Receiver</soap:Value></soap:Code>
                <soap:Reason><soap:Text xml:lang="en">external failure</soap:Text></soap:Reason>
                """;
        byte[] responseBody = this.faultEnvelope(fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.writeFault(exchange, responseBody));
        server.start();
        try {
            // Meteor 通过公开 Bootstrap 订阅服务，连接真实 HTTP 故障对端。
            MetConfiguration configuration = this.configuration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方应立即收到相应协议失败，而不是空成功或超时。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "failure" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IllegalArgumentException);
                String message = failure.getCause().getMessage();
                assertTrue(message, message.contains("Invalid SOAP Fault QName"));
            }
        } finally {
            server.stop(0);
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .protocol("soap") //
                .option("soapVersion", "1.2") //
                .contextPath("/services");
        return configuration;
    }

    private InterAddress address(HttpServer server) {
        return new InterAddress("soap", "127.0.0.1", server.getAddress().getPort(), "default");
    }

    private byte[] faultEnvelope(String fault) {
        String opening = "<soap:Envelope xmlns:soap=\"" + NAMESPACE + "\"><soap:Body>";
        String envelope = opening + "<soap:Fault>" + fault + "</soap:Fault></soap:Body></soap:Envelope>";
        return envelope.getBytes(StandardCharsets.UTF_8);
    }

    private void writeFault(HttpExchange exchange, byte[] responseBody) {
        try (exchange) {
            // 消费请求后返回标准 SOAP 1.2 媒体类型和错误状态。
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/soap+xml; charset=utf-8");
            exchange.sendResponseHeaders(500, responseBody.length);
            exchange.getResponseBody().write(responseBody);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to write external SOAP Fault", failure);
        }
    }
}
