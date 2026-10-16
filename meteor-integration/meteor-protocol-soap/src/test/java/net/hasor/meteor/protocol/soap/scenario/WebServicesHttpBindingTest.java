/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.protocol.soap.support.EchoService;
import net.hasor.meteor.protocol.soap.support.SignalService;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebServicesHttpBindingTest {
    @Test(timeout = 20000)
    public void invalidSoapContentTypeIsRejectedBeforeInvocation() throws Exception {
        // 启动公开 SOAP 端点，并记录业务方法是否被调用。
        AtomicInteger invocations = new AtomicInteger();
        MetConfiguration configuration = this.incomingConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 使用近似但无效的媒体类型发送格式正确的 SOAP 正文。
            URI endpoint = this.endpoint(context, "EchoService");
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", "text/xmlfoo") //
                    .header("SOAPAction", "\"urn:hasor:meteor:soap:EchoService/echo\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.echoEnvelope("invalid"))) //
                    .build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());

            // HTTP 绑定拒绝请求，业务服务没有收到调用。
            assertEquals(415, response.statusCode());
            assertEquals(0, invocations.get());
        }
    }

    @Test(timeout = 20000)
    public void requestUsesMimeCharsetWhenXmlDeclarationIsAbsent() throws Exception {
        // 发布一个记录原始参数的 SOAP 服务。
        AtomicReference<String> received = new AtomicReference<>();
        MetConfiguration configuration = this.incomingConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            EchoService service = message -> {
                received.set(message);
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 正文没有 XML 声明，字符集只由 HTTP Content-Type 指定。
            String message = "caf\u00e9 d\u00e9j\u00e0";
            byte[] body = this.echoEnvelopeWithoutDeclaration(message, StandardCharsets.ISO_8859_1);
            URI endpoint = this.endpoint(context, "EchoService");
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", "text/xml; charset=ISO-8859-1") //
                    .header("SOAPAction", "\"urn:hasor:meteor:soap:EchoService/echo\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)) //
                    .build();
            HttpResponse<String> response = this.http().send(request, //
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // 服务端按照 MIME 字符集还原参数，响应仍使用插件声明的 UTF-8。
            assertEquals(200, response.statusCode());
            assertEquals(message, received.get());
            assertTrue(response.body(), response.body().contains(message));
        }
    }

    @Test(timeout = 20000)
    public void duplicateSoapActionIsRejectedBeforeInvocation() throws Exception {
        // 启动公开 SOAP 端点，并记录业务方法是否被调用。
        AtomicInteger invocations = new AtomicInteger();
        MetConfiguration configuration = this.incomingConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // JDK 客户端在真实 HTTP 请求中保留两个同名 SOAPAction 字段。
            URI endpoint = this.endpoint(context, "EchoService");
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"urn:hasor:meteor:soap:EchoService/echo\"") //
                    .header("SOAPAction", "\"urn:hasor:meteor:soap:EchoService/another\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.echoEnvelope("duplicate"))) //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // 歧义字段形成 SOAP Client Fault，业务方法没有开始执行。
            assertEquals(500, response.statusCode());
            assertTrue(response.body(), response.body().contains("soap:Client"));
            assertEquals(0, invocations.get());
        }
    }

    @Test(timeout = 20000)
    public void missingSoapActionIsRejectedBeforeInvocation() throws Exception {
        // 启动公开 SOAP 端点，并记录业务方法是否被调用。
        AtomicInteger invocations = new AtomicInteger();
        MetConfiguration configuration = this.incomingConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // SOAP 正文有效，但请求故意不发送 SOAPAction。
            URI endpoint = this.endpoint(context, "EchoService");
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.echoEnvelope("missing action"))) //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // 缺失必需的 action 字段形成 Client Fault，业务方法没有执行。
            assertEquals(500, response.statusCode());
            assertTrue(response.body(), response.body().contains("soap:Client"));
            assertEquals(0, invocations.get());
        }
    }

    @Test(timeout = 20000)
    public void mismatchedSoapActionIsRejectedBeforeInvocation() throws Exception {
        // 启动公开 SOAP 端点，并记录业务方法是否被调用。
        AtomicInteger invocations = new AtomicInteger();
        MetConfiguration configuration = this.incomingConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 请求的 SOAPAction 与正文中的 echo operation 不匹配。
            URI endpoint = this.endpoint(context, "EchoService");
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"urn:hasor:meteor:soap:EchoService/another\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.echoEnvelope("wrong action"))) //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // 错误 action 形成 Client Fault，业务方法没有执行。
            assertEquals(500, response.statusCode());
            assertTrue(response.body(), response.body().contains("soap:Client"));
            assertEquals(0, invocations.get());
        }
    }

    @Test(timeout = 20000)
    public void oneWayAcceptsEmptyHttp200() throws Exception {
        // 启动真实 HTTP 对端，接收请求后返回无正文的 200。
        AtomicReference<String> received = new AtomicReference<>();
        CountDownLatch invoked = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/SignalService", exchange -> {
            try (exchange) {
                received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                invoked.countDown();
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        try {
            // 通过公开 Bootstrap API 订阅自动生成契约的 one-way 服务。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<SignalService> service = context.publisher() //
                        .rsfService(SignalService.class) //
                        .aliasName("soap", "SignalService") //
                        .protocol("soap") //
                        .register();

                // 空 200 是 one-way 调用的成功确认。
                MetClient client = context.getRsfClient(this.address(server));
                SignalService remote = client.getRemote(service);
                remote.signal("notice");

                // 对端真实收到请求，调用方正常完成。
                assertTrue("SOAP peer did not receive the notification", invoked.await(5, TimeUnit.SECONDS));
                assertTrue(received.get(), received.get().contains("notice"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void oneWayAcceptsEmptyHttp202() throws Exception {
        // 启动真实 HTTP 对端，接收请求后返回无正文的 202。
        AtomicReference<String> received = new AtomicReference<>();
        CountDownLatch invoked = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/SignalService", exchange -> {
            try (exchange) {
                received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                invoked.countDown();
                exchange.sendResponseHeaders(202, -1);
            }
        });
        server.start();
        try {
            // 通过公开 Bootstrap API 订阅自动生成契约的 one-way 服务。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<SignalService> service = context.publisher() //
                        .rsfService(SignalService.class) //
                        .aliasName("soap", "SignalService") //
                        .protocol("soap") //
                        .register();

                // 空 202 是 one-way 调用的成功确认。
                MetClient client = context.getRsfClient(this.address(server));
                SignalService remote = client.getRemote(service);
                remote.signal("notice");

                // 对端真实收到请求，调用方正常完成。
                assertTrue("SOAP peer did not receive the notification", invoked.await(5, TimeUnit.SECONDS));
                assertTrue(received.get(), received.get().contains("notice"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void oneWayAcceptsEmptyHttp204() throws Exception {
        // 启动真实 HTTP 对端，接收请求后返回无正文的 204。
        AtomicReference<String> received = new AtomicReference<>();
        CountDownLatch invoked = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/SignalService", exchange -> {
            try (exchange) {
                received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                invoked.countDown();
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        try {
            // 通过公开 Bootstrap API 订阅自动生成契约的 one-way 服务。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<SignalService> service = context.publisher() //
                        .rsfService(SignalService.class) //
                        .aliasName("soap", "SignalService") //
                        .protocol("soap") //
                        .register();

                // 空 204 是 one-way 调用的成功确认。
                MetClient client = context.getRsfClient(this.address(server));
                SignalService remote = client.getRemote(service);
                remote.signal("notice");

                // 对端真实收到请求，调用方正常完成。
                assertTrue("SOAP peer did not receive the notification", invoked.await(5, TimeUnit.SECONDS));
                assertTrue(received.get(), received.get().contains("notice"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void oneWayRejectsNonEmptyHttp202() throws Exception {
        // 启动真实 HTTP 对端，为 one-way 调用返回带正文的 202。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/SignalService", exchange -> {
            byte[] body = this.acknowledgementEnvelope();
            this.writeResponse(exchange, 202, "text/xml; charset=utf-8", body);
        });
        server.start();
        try {
            // 通过公开 Bootstrap API 订阅自动生成契约的 one-way 服务。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<SignalService> service = context.publisher() //
                        .rsfService(SignalService.class) //
                        .aliasName("soap", "SignalService") //
                        .protocol("soap") //
                        .register();

                // 非空成功响应违反 one-way HTTP 绑定，调用方必须感知失败。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "notice" };
                MetFuture invocation = client.asyncInvoke(service, "signal", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));

                Throwable cause = failure.getCause();
                assertTrue(cause instanceof IllegalArgumentException);
                assertTrue(cause.getMessage(), cause.getMessage().contains("must return an empty HTTP"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void responseUsesMimeCharsetWhenXmlDeclarationIsAbsent() throws Exception {
        // 启动真实 HTTP 对端，返回无 XML 声明的 ISO-8859-1 SOAP 响应。
        String result = "caf\u00e9 \u00e9t\u00e9";
        byte[] responseBody = this.echoResponseWithoutDeclaration(result, StandardCharsets.ISO_8859_1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> {
            this.writeResponse(exchange, 200, "text/xml; charset=ISO-8859-1", responseBody);
        });
        server.start();
        try {
            // 通过公开 Bootstrap API 订阅普通 SOAP 服务。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 响应字符集仅由 MIME 参数声明，Meteor 仍能还原业务结果。
                EchoService remote = context.getRsfClient(this.address(server)).getRemote(service);
                assertEquals(result, remote.echo("request"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void responseRejectsUnknownMandatorySoapHeader() throws Exception {
        // 启动真实 HTTP 对端，返回发给当前节点的未知必选 SOAP Header。
        byte[] responseBody = this.echoResponseWithMandatoryHeader();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> {
            this.writeResponse(exchange, 200, "text/xml; charset=utf-8", responseBody);
        });
        server.start();
        try {
            // 通过公开 Bootstrap API 订阅普通 SOAP 服务。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 响应解码必须拒绝当前插件未声明处理能力的必选 Header。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "request" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));

                Throwable cause = failure.getCause();
                assertTrue(cause instanceof IllegalArgumentException);
                assertTrue(cause.getMessage(), cause.getMessage().contains("SOAP header is not understood"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void requestResponseRejectsEmptyHttp204() throws Exception {
        // 外部对端对普通双向调用返回空 204，没有返回生成契约要求的结果。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        try {
            // 通过公开入口订阅普通 Java 接口并启动 SOAP 客户端。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 普通调用不能把无正文的成功状态当作业务调用成功。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "request" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                Throwable cause = failure.getCause();
                assertTrue(cause instanceof IllegalArgumentException);
                assertTrue(cause.getMessage(), cause.getMessage().contains("HTTP 204 response has no envelope"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void requestResponseRejectsBusinessEnvelopeWithHttp202() throws Exception {
        // 外部对端返回合法业务结果，但使用双向调用不接受的 HTTP 202。
        byte[] responseBody = this.echoResponseWithoutDeclaration("unexpected", StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> {
            this.writeResponse(exchange, 202, "text/xml; charset=utf-8", responseBody);
        });
        server.start();
        try {
            // 通过公开入口订阅普通 Java 接口并启动 SOAP 客户端。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方收到调用失败状态，并能诊断实际的 HTTP 状态。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "request" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof MetException);
                MetException fault = (MetException) failure.getCause();
                assertEquals(ProtocolStatus.InvokeError, fault.getStatus());
                assertTrue(fault.getMessage(), fault.getMessage().contains("HTTP status 202"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void responseRejectsInvalidSoapContentType() throws Exception {
        // 外部对端返回合法 SOAP 正文，但使用近似的无效媒体类型。
        byte[] responseBody = this.echoResponseWithoutDeclaration("unexpected", StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> {
            this.writeResponse(exchange, 200, "text/xmlfoo; charset=utf-8", responseBody);
        });
        server.start();
        try {
            // 通过公开入口订阅普通 Java 接口并启动 SOAP 客户端。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 正文即使可解析，错误的 SOAP 媒体类型仍使调用失败。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "request" };
                MetFuture invocation = client.asyncInvoke(service, "echo", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                Throwable cause = failure.getCause();
                assertTrue(cause instanceof IllegalArgumentException);
                assertTrue(cause.getMessage(), cause.getMessage().contains("Content-Type must be text/xml"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void repeatedBusinessResponseHeaderMapsItsFirstValue() throws Exception {
        // 启动真实 HTTP 对端，返回同名业务响应头的两个值。
        byte[] responseBody = this.echoResponseWithoutDeclaration("headers", StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> {
            exchange.getResponseHeaders().add("X-Trace", "first");
            exchange.getResponseHeaders().add("X-Trace", "second");
            this.writeResponse(exchange, 200, "text/xml; charset=utf-8", responseBody);
        });
        server.start();
        try {
            // 通过公开 Bootstrap API 订阅服务并保留完整 MetResponse。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 同名业务头只映射首个值，业务结果正常交付。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "headers" };
                MetResponse response = client.asyncInvoke(service, "echo", parameterTypes, arguments) //
                        .get(5, TimeUnit.SECONDS);

                assertEquals("headers", response.getData());
                assertEquals("first", this.option(response, "X-Trace"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void reservedResponseHeadersStayInternal() throws Exception {
        // 启动真实 HTTP 对端，在正常 SOAP 响应中携带协议控制头。
        byte[] responseBody = this.echoResponseWithoutDeclaration("headers", StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> {
            exchange.getResponseHeaders().add("SOAPAction", "internal action");
            exchange.getResponseHeaders().add("Message", "internal message");
            this.writeResponse(exchange, 200, "text/xml; charset=utf-8", responseBody);
        });
        server.start();
        try {
            // 通过公开 Bootstrap API 订阅服务并保留完整 MetResponse。
            MetConfiguration configuration = this.outgoingConfiguration();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // SOAP 与 HTTP 控制头不暴露为 RPC option，业务结果仍正常交付。
                MetClient client = context.getRsfClient(this.address(server));
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "headers" };
                MetResponse response = client.asyncInvoke(service, "echo", parameterTypes, arguments) //
                        .get(5, TimeUnit.SECONDS);

                assertEquals("headers", response.getData());
                assertNull(this.option(response, "Content-Type"));
                assertNull(this.option(response, "Content-Length"));
                assertNull(this.option(response, "SOAPAction"));
                assertNull(this.option(response, "Message"));
            }
        } finally {
            server.stop(0);
        }
    }

    private MetConfiguration incomingConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        return configuration;
    }

    private MetConfiguration outgoingConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .protocol("soap") //
                .contextPath("/services");
        return configuration;
    }

    private URI endpoint(MetContext context, String serviceName) {
        int port = context.bindAddress("soap").getPort();
        return URI.create("http://127.0.0.1:" + port + "/services/" + serviceName);
    }

    private InterAddress address(HttpServer server) {
        return new InterAddress("soap", "127.0.0.1", server.getAddress().getPort(), "default");
    }

    private HttpClient http() {
        return HttpClient.newBuilder() //
                .connectTimeout(Duration.ofSeconds(15)) //
                .version(HttpClient.Version.HTTP_1_1) //
                .build();
    }

    private void writeResponse(HttpExchange exchange, int status, String contentType, byte[] body) {
        try (exchange) {
            // 完整消费请求后返回固定 SOAP 或绑定层响应。
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to write HTTP test response", failure);
        }
    }

    private String option(MetResponse response, String name) {
        for (String key : response.getOptionKeys()) {
            if (StringUtils.equalsIgnoreCase(key, name)) {
                return response.getOption(key);
            }
        }
        return null;
    }

    private byte[] echoEnvelope(String message) {
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                    <soap:Body>
                        <m:echo xmlns:m="urn:hasor:meteor:soap:EchoService">
                            <arg0>%s</arg0>
                        </m:echo>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(message);
        return envelope.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] echoEnvelopeWithoutDeclaration(String message, Charset charset) {
        String envelope = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                    <soap:Body>
                        <m:echo xmlns:m="urn:hasor:meteor:soap:EchoService">
                            <arg0>%s</arg0>
                        </m:echo>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(message);
        return envelope.getBytes(charset);
    }

    private byte[] echoResponseWithoutDeclaration(String result, Charset charset) {
        String envelope = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                    <soap:Body>
                        <m:echoResponse xmlns:m="urn:hasor:meteor:soap:EchoService">
                            <return>%s</return>
                        </m:echoResponse>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(result);
        return envelope.getBytes(charset);
    }

    private byte[] echoResponseWithMandatoryHeader() {
        String envelope = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                               xmlns:test="urn:meteor:test:header">
                    <soap:Header>
                        <test:Required soap:mustUnderstand="1"/>
                    </soap:Header>
                    <soap:Body>
                        <m:echoResponse xmlns:m="urn:hasor:meteor:soap:EchoService">
                            <return>ignored</return>
                        </m:echoResponse>
                    </soap:Body>
                </soap:Envelope>
                """;
        return envelope.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] acknowledgementEnvelope() {
        String envelope = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                    <soap:Body>
                        <signalResponse xmlns="urn:hasor:meteor:soap:SignalService"/>
                    </soap:Body>
                </soap:Envelope>
                """;
        return envelope.getBytes(StandardCharsets.UTF_8);
    }
}
