/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.hprose.TextService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.hprose.support.HproseTlsSupport;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseTlsTest {
    @Test(timeout = 20000)
    public void h2PriorKnowledgeCallsPublishedService() throws Exception {
        // 明文 HTTP/2 客户端直接发送 preface，服务端按实际报文选择协议。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose").contextPath("/rpc");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        outgoing.connector("http") //
                .option("httpVersion", 2) //
                .protocol("hprose").contextPath("/rpc");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动独立上下文后发布和订阅，Hprose 使用自身固定的线协议编码。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .aliasName("Hprose", "echo") //
                    .register();
            MetBindInfo<TextService> service = client.publisher() //
                    .rsfService(TextService.class) //
                    .aliasName("Hprose", "echo") //
                    .register();

            // 从公开客户端取得代理，动态监听地址保证调用经过真实网络。
            TextService remote = client.getRsfClient(server.bindAddress("hprose")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void httpsHttp1CallsPublishedService() throws Exception {
        // 单向 TLS 仅允许协商 HTTP/1.1，客户端验证服务器证书和主机名。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "http/1.1") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        outgoing.connector("http") //
                .option("httpVersion", 1) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsAlpn", "http/1.1") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动独立上下文后发布和订阅，Hprose 使用自身固定的线协议编码。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .aliasName("Hprose", "echo") //
                    .register();
            MetBindInfo<TextService> service = client.publisher() //
                    .rsfService(TextService.class) //
                    .aliasName("Hprose", "echo") //
                    .register();

            // 从公开客户端取得代理，动态监听地址保证调用经过真实网络。
            TextService remote = client.getRsfClient(server.bindAddress("hprose")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void httpsHttp2AlpnCallsPublishedService() throws Exception {
        // 单向 TLS 仅允许协商 h2，HTTP/2 客户端不会回退到 HTTP/1.1。
        MetConfiguration incoming = new MetConfiguration();
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
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        outgoing.connector("http") //
                .option("httpVersion", 2) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsAlpn", "h2") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动独立上下文后发布和订阅，Hprose 使用自身固定的线协议编码。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .aliasName("Hprose", "echo") //
                    .register();
            MetBindInfo<TextService> service = client.publisher() //
                    .rsfService(TextService.class) //
                    .aliasName("Hprose", "echo") //
                    .register();

            // 从公开客户端取得代理，动态监听地址保证调用经过真实网络。
            TextService remote = client.getRsfClient(server.bindAddress("hprose")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void mutualTlsHttp1CallsPublishedService() throws Exception {
        // HTTP/1.1 两端分别加载身份，服务端要求客户端通过证书认证。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "http/1.1") //
                .option("tlsClientAuth", "REQUIRE") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        outgoing.connector("http") //
                .option("httpVersion", 1) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/client.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "http/1.1") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动独立上下文后发布和订阅，Hprose 使用自身固定的线协议编码。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .aliasName("Hprose", "echo") //
                    .register();
            MetBindInfo<TextService> service = client.publisher() //
                    .rsfService(TextService.class) //
                    .aliasName("Hprose", "echo") //
                    .register();

            // 从公开客户端取得代理，动态监听地址保证调用经过真实网络。
            TextService remote = client.getRsfClient(server.bindAddress("hprose")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void mutualTlsHttp2AlpnCallsPublishedService() throws Exception {
        // HTTP/2 在 ALPN h2 上双向认证，两端使用独立的客户端和服务器证书。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "h2") //
                .option("tlsClientAuth", "REQUIRE") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        outgoing.connector("http") //
                .option("httpVersion", 2) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/client.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "h2") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动独立上下文后发布和订阅，Hprose 使用自身固定的线协议编码。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .aliasName("Hprose", "echo") //
                    .register();
            MetBindInfo<TextService> service = client.publisher() //
                    .rsfService(TextService.class) //
                    .aliasName("Hprose", "echo") //
                    .register();

            // 从公开客户端取得代理，动态监听地址保证调用经过真实网络。
            TextService remote = client.getRsfClient(server.bindAddress("hprose")).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void mutualTlsHttp1RejectsMissingClientCertificateBeforeDispatch() throws Exception {
        // 服务端要求客户端证书，只安装信任库的调用方不能进入业务实现。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "http/1.1") //
                .option("tlsClientAuth", "REQUIRE") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");
        try (MetContext server = incoming.buildContext()) {
            // 容器启动后发布带计数的普通业务方法，观察握手失败的隔离边界。
            server.start();
            AtomicInteger calls = new AtomicInteger();
            server.publisher() //
                    .rsfService(TextService.class, value -> {
                        calls.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("Hprose", "echo") //
                    .register();

            // 外部客户端信任服务器，但不提供任何客户端身份。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(HproseTlsSupport.trustedClientContext()) //
                    .version(HttpClient.Version.HTTP_1_1) //
                    .connectTimeout(Duration.ofSeconds(5)) //
                    .build();
            int port = server.bindAddress("hprose").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc");
            String call = "Cs9\"echo_echo\"a1{s5\"hello\"}z";
            HttpRequest request = HttpRequest.newBuilder(uri) //
                    .timeout(Duration.ofSeconds(5)) //
                    .POST(HttpRequest.BodyPublishers.ofString(call)) //
                    .build();

            // TLS 必须主动拒绝身份缺失；超时不能作为拒绝证据。
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofString());
            });
            assertFalse("Certificate rejection must not time out", failure instanceof HttpTimeoutException);
            assertEquals(0, calls.get());
        }
    }

    @Test(timeout = 20000)
    public void mutualTlsHttp2RejectsMissingClientCertificateBeforeDispatch() throws Exception {
        // 服务端要求客户端证书，只安装信任库的调用方不能进入业务实现。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "h2") //
                .option("tlsClientAuth", "REQUIRE") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");
        try (MetContext server = incoming.buildContext()) {
            // 容器启动后发布带计数的普通业务方法，观察握手失败的隔离边界。
            server.start();
            AtomicInteger calls = new AtomicInteger();
            server.publisher() //
                    .rsfService(TextService.class, value -> {
                        calls.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("Hprose", "echo") //
                    .register();

            // 外部客户端信任服务器，但不提供任何客户端身份。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(HproseTlsSupport.trustedClientContext()) //
                    .version(HttpClient.Version.HTTP_2) //
                    .connectTimeout(Duration.ofSeconds(5)) //
                    .build();
            int port = server.bindAddress("hprose").getPort();
            URI uri = URI.create("https://127.0.0.1:" + port + "/rpc");
            String call = "Cs9\"echo_echo\"a1{s5\"hello\"}z";
            HttpRequest request = HttpRequest.newBuilder(uri) //
                    .timeout(Duration.ofSeconds(5)) //
                    .POST(HttpRequest.BodyPublishers.ofString(call)) //
                    .build();

            // TLS 必须主动拒绝身份缺失；超时不能作为拒绝证据。
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofString());
            });
            assertFalse("Certificate rejection must not time out", failure instanceof HttpTimeoutException);
            assertEquals(0, calls.get());
        }
    }
}
