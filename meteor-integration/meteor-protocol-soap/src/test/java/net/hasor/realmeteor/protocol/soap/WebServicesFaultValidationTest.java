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
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WebServicesFaultValidationTest {

    @Test(timeout = 20000)
    public void subcodeQNameMustStartWithAnXmlNameCharacter() throws Exception {
        // Subcode 的 QName 局部名不能以数字开头。
        String fault = """
                <s:Code><s:Value>s:Sender</s:Value>
                <s:Subcode><s:Value>s:1invalid</s:Value></s:Subcode></s:Code>
                <s:Reason>
                <s:Text xml:lang="en">bad</s:Text></s:Reason>
                """;
        byte[] responseBody = this.envelope(fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.writeFault(exchange, responseBody));
        server.start();
        try {
            // 从公开 Bootstrap 发起真实 HTTP 请求，接收对端的畸形 Fault。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
            configuration.connector("http") //
                    .protocol("soap") //
                    .option("soapVersion", "1.2") //
                    .contextPath("/services");
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方获得明确的格式错误，不能把畸形结构解释成有效远端 Fault。
                InterAddress address = new InterAddress("soap", "127.0.0.1", server.getAddress().getPort(), "default");
                MetFuture invocation = context.getRsfClient(address) //
                        .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "request" });
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

    @Test(timeout = 20000)
    public void faultQNameCannotContainWhitespace() throws Exception {
        // 已绑定前缀也不能使包含空格的 QName 合法。
        String fault = """
                <s:Code><s:Value>s:bad name</s:Value></s:Code>
                <s:Reason>
                <s:Text xml:lang="en">bad</s:Text></s:Reason>
                """;
        byte[] responseBody = this.envelope(fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.writeFault(exchange, responseBody));
        server.start();
        try {
            // 从公开 Bootstrap 发起真实 HTTP 请求，接收对端的畸形 Fault。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
            configuration.connector("http") //
                    .protocol("soap") //
                    .option("soapVersion", "1.2") //
                    .contextPath("/services");
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方获得明确的格式错误，不能把畸形结构解释成有效远端 Fault。
                InterAddress address = new InterAddress("soap", "127.0.0.1", server.getAddress().getPort(), "default");
                MetFuture invocation = context.getRsfClient(address) //
                        .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "request" });
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

    @Test(timeout = 20000)
    public void reasonCannotPrecedeCode() throws Exception {
        // SOAP 1.2 Fault 的 Code 必须排在 Reason 之前。
        String fault = """
                <s:Reason>
                <s:Text xml:lang="en">bad</s:Text></s:Reason>
                <s:Code><s:Value>s:Sender</s:Value></s:Code>
                """;
        byte[] responseBody = this.envelope(fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.writeFault(exchange, responseBody));
        server.start();
        try {
            // 从公开 Bootstrap 发起真实 HTTP 请求，接收对端的畸形 Fault。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
            configuration.connector("http") //
                    .protocol("soap") //
                    .option("soapVersion", "1.2") //
                    .contextPath("/services");
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方获得明确的格式错误，不能把畸形结构解释成有效远端 Fault。
                InterAddress address = new InterAddress("soap", "127.0.0.1", server.getAddress().getPort(), "default");
                MetFuture invocation = context.getRsfClient(address) //
                        .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "request" });
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IllegalArgumentException);
                String message = failure.getCause().getMessage();
                assertTrue(message, message.contains("Invalid SOAP 1.2 Fault element order"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void faultRoleCannotOccurTwice() throws Exception {
        // SOAP 1.2 Fault 的 Role 是单值字段。
        String fault = """
                <s:Code><s:Value>s:Sender</s:Value></s:Code>
                <s:Reason>
                <s:Text xml:lang="en">bad</s:Text></s:Reason>
                <s:Role>urn:one</s:Role><s:Role>urn:two</s:Role>
                """;
        byte[] responseBody = this.envelope(fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.writeFault(exchange, responseBody));
        server.start();
        try {
            // 从公开 Bootstrap 发起真实 HTTP 请求，接收对端的畸形 Fault。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
            configuration.connector("http") //
                    .protocol("soap") //
                    .option("soapVersion", "1.2") //
                    .contextPath("/services");
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方获得明确的格式错误，不能把畸形结构解释成有效远端 Fault。
                InterAddress address = new InterAddress("soap", "127.0.0.1", server.getAddress().getPort(), "default");
                MetFuture invocation = context.getRsfClient(address) //
                        .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "request" });
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IllegalArgumentException);
                String message = failure.getCause().getMessage();
                assertTrue(message, message.contains("Invalid SOAP 1.2 Fault element order"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void reasonTextCannotHideNestedElements() throws Exception {
        // Reason/Text 只允许字符内容，不能拼接嵌套 XML 得到错误消息。
        String fault = """
                <s:Code><s:Value>s:Sender</s:Value></s:Code>
                <s:Reason>
                <s:Text xml:lang="en"><x:Hidden xmlns:x="urn:test">bad</x:Hidden></s:Text></s:Reason>
                """;
        byte[] responseBody = this.envelope(fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.writeFault(exchange, responseBody));
        server.start();
        try {
            // 从公开 Bootstrap 发起真实 HTTP 请求，接收对端的畸形 Fault。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
            configuration.connector("http") //
                    .protocol("soap") //
                    .option("soapVersion", "1.2") //
                    .contextPath("/services");
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方获得明确的格式错误，不能把畸形结构解释成有效远端 Fault。
                InterAddress address = new InterAddress("soap", "127.0.0.1", server.getAddress().getPort(), "default");
                MetFuture invocation = context.getRsfClient(address) //
                        .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "request" });
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IllegalArgumentException);
                String message = failure.getCause().getMessage();
                assertTrue(message, message.contains("must contain text only"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void subcodeRequiresValueBeforeNestedSubcode() throws Exception {
        // 每层 Subcode 都必须有自己的 Value。
        String fault = """
                <s:Code><s:Value>s:Sender</s:Value>
                <s:Subcode>
                <s:Subcode><s:Value>s:Nested</s:Value></s:Subcode></s:Subcode></s:Code>
                <s:Reason>
                <s:Text xml:lang="en">bad</s:Text></s:Reason>
                """;
        byte[] responseBody = this.envelope(fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.writeFault(exchange, responseBody));
        server.start();
        try {
            // 从公开 Bootstrap 发起真实 HTTP 请求，接收对端的畸形 Fault。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
            configuration.connector("http") //
                    .protocol("soap") //
                    .option("soapVersion", "1.2") //
                    .contextPath("/services");
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .register();

                // 调用方获得明确的格式错误，不能把畸形结构解释成有效远端 Fault。
                InterAddress address = new InterAddress("soap", "127.0.0.1", server.getAddress().getPort(), "default");
                MetFuture invocation = context.getRsfClient(address) //
                        .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "request" });
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IllegalArgumentException);
                String message = failure.getCause().getMessage();
                assertTrue(message, message.contains("SOAP Fault Code must start with Value"));
            }
        } finally {
            server.stop(0);
        }
    }

    private byte[] envelope(String fault) {
        String body = """
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
                    <s:Body><s:Fault>%s</s:Fault></s:Body>
                </s:Envelope>
                """.formatted(fault);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private void writeFault(HttpExchange exchange, byte[] body) {
        try (exchange) {
            // 消费请求后返回畸形 SOAP 1.2 Fault，HTTP 层仍是完整响应。
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/soap+xml; charset=utf-8");
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to return Fault", failure);
        }
    }
}
