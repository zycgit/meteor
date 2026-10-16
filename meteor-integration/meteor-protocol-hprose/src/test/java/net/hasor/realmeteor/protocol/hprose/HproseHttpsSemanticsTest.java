/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import hprose.io.HproseReader;
import net.hasor.example.protocol.hprose.Sample;
import net.hasor.example.protocol.hprose.SampleImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.hprose.support.HproseTlsSupport;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HproseHttpsSemanticsTest {
    @Test(timeout = 20000)
    public void httpsHttp2ReturnsFunctionDiscovery() throws Exception {
        // HTTPS 仅协商 h2，服务仍由 bootstrap 启动后通过普通发布接口注册。
        MetConfiguration incoming = new MetConfiguration().setQueueMinPoolSize(4);
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "h2") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");
        try (MetContext server = incoming.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 独立 JDK 客户端校验证书和主机名，响应版本确认实际使用 HTTP/2。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(HproseTlsSupport.trustedClientContext()) //
                    .version(HttpClient.Version.HTTP_2) //
                    .build();
            int port = server.getListenAddress("hprose").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc");

            // GET 发现应返回 Hprose 函数表，包含发布接口中的公开方法。
            HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
            HttpResponse<String> response = this.send(client, request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertTrue(response.body().startsWith("F"));
            assertTrue(response.body().contains("sample_echo"));
            assertTrue(response.body().contains("sample_mutate"));
            assertTrue(response.body().endsWith("z"));
        }
    }

    @Test(timeout = 20000)
    public void httpsHttp2ReturnsByRefArguments() throws Exception {
        // HTTPS 仅协商 h2，服务仍由 bootstrap 启动后通过普通发布接口注册。
        MetConfiguration incoming = new MetConfiguration().setQueueMinPoolSize(4);
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "h2") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");
        try (MetContext server = incoming.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 独立 JDK 客户端校验证书和主机名，响应版本确认实际使用 HTTP/2。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(HproseTlsSupport.trustedClientContext()) //
                    .version(HttpClient.Version.HTTP_2) //
                    .build();
            int port = server.getListenAddress("hprose").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc");

            // byref 的 t 标志要求服务端把修改后的参数通过 A 段一并返回。
            String call = "Cs13\"sample_mutate\"a1{a1{s6\"before\"}}tz";
            HttpRequest request = HttpRequest.newBuilder(uri) //
                    .POST(HttpRequest.BodyPublishers.ofString(call)) //
                    .build();
            HttpResponse<String> response = this.send(client, request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals("RnAa1{a2{s6\"before\"s5\"after\"}}z", response.body());
        }
    }

    @Test(timeout = 20000)
    public void httpsHttp2BatchKeepsOrderAndIsolatesBusinessFailure() throws Exception {
        // HTTPS 仅协商 h2，服务仍由 bootstrap 启动后通过普通发布接口注册。
        MetConfiguration incoming = new MetConfiguration().setQueueMinPoolSize(4);
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "h2") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");
        try (MetContext server = incoming.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 独立 JDK 客户端校验证书和主机名，响应版本确认实际使用 HTTP/2。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(HproseTlsSupport.trustedClientContext()) //
                    .version(HttpClient.Version.HTTP_2) //
                    .build();
            int port = server.getListenAddress("hprose").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc");

            // 同一批次包含慢成功、业务异常和普通成功，结果必须保持提交顺序。
            String first = "Cs12\"sample_delay\"a2{s5\"first\"i80;}";
            String failed = "Cs11\"sample_fail\"";
            String last = "Cs11\"sample_echo\"a1{s5\"third\"}z";
            String batch = first + failed + last;
            HttpRequest request = HttpRequest.newBuilder(uri) //
                    .POST(HttpRequest.BodyPublishers.ofString(batch)) //
                    .build();
            HttpResponse<String> response = this.send(client, request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, response.version());

            // 使用官方 Hprose 读取器逐段解析固定线协议，不将错误文本当作可换的 JSON 编码。
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            HproseReader reader = new HproseReader(input);
            reader.checkTag('R');
            assertEquals("first", reader.readString());
            reader.reset();
            reader.checkTag('E');
            String error = reader.readString();
            assertTrue(error, error.contains("business failure"));
            reader.reset();
            reader.checkTag('R');
            assertEquals("third", reader.readString());
            assertEquals('z', input.read());
            assertEquals(-1, input.read());

            // 当前批次失败不污染随后独立调用，后续响应仍走同一客户端的 HTTP/2 路径。
            String echo = "Cs11\"sample_echo\"a1{s11\"after error\"}z";
            HttpRequest following = HttpRequest.newBuilder(uri) //
                    .POST(HttpRequest.BodyPublishers.ofString(echo)) //
                    .build();
            HttpResponse<String> recovered = this.send(client, following, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, recovered.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, recovered.version());
            assertEquals("Rs11\"after error\"z", recovered.body());
        }
    }

    private <T> HttpResponse<T> send(HttpClient client, HttpRequest request, //
            HttpResponse.BodyHandler<T> handler) throws Exception {
        Future<HttpResponse<T>> pending = client.sendAsync(request, handler);
        try {
            return pending.get(5, TimeUnit.SECONDS);
        } finally {
            pending.cancel(true);
        }
    }
}
