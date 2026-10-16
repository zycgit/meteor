/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose;
import net.hasor.example.protocol.hprose.TextService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Hprose 通用序列化器可以用于 RSFS；HTTP/Hprose 的固定线协议仍是独立能力。 */
public class HproseRsfsSerializationTest {
    @Test(timeout = 20000)
    public void hproseSerializationOverRsfsWithServerAuthentication() throws Exception {
        // TCP 端点启用 TLS，单向认证客户端只加载服务器 CA 的信任库。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("tcp") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .protocol("rsf").addressScheme("rsfs");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        outgoing.connector("tcp") //
                .option("tlsEnabled", true) //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .protocol("rsf").addressScheme("rsfs");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后显式选择 RSFS 与 Hprose 编码，业务接口来自本插件示例。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> "secure:" + value) //
                    .protocol("rsfs").serialize("Hprose").register();
            MetBindInfo<TextService> service = client.publisher() //
                    .rsfService(TextService.class) //
                    .protocol("rsfs").serialize("Hprose").register();

            // 远端地址保持 rsfs，中文及补充字符经过加密连接与通用编码器往返。
            TextService remote = client.getRsfClient(server.getListenAddress("rsf", "rsfs")).getRemote(service);
            assertEquals("rsfs", server.getListenAddress("rsf", "rsfs").getSchema());
            assertEquals("secure:Hprose中文🙂", remote.echo("Hprose中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void hproseSerializationOverRsfsWithMutualTls() throws Exception {
        // 双向 TLS 使用独立身份，服务端要求客户端提供受信任的证书。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("tcp") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsClientAuth", "REQUIRE") //
                .protocol("rsf").addressScheme("rsfs");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        outgoing.connector("tcp") //
                .option("tlsEnabled", true) //
                .option("tlsKeyStore", "classpath:/tls/acceptance/client.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .protocol("rsf").addressScheme("rsfs");

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布和订阅均通过公开入口，Hprose SPI 编码器由插件自动加载。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> "secure:" + value) //
                    .protocol("rsfs").serialize("Hprose").register();
            MetBindInfo<TextService> service = client.publisher() //
                    .rsfService(TextService.class) //
                    .protocol("rsfs").serialize("Hprose").register();

            // 通过 RSFS 远程代理调用，验证双向认证后的业务参数与结果。
            TextService remote = client.getRsfClient(server.getListenAddress("rsf", "rsfs")).getRemote(service);
            assertEquals("rsfs", server.getListenAddress("rsf", "rsfs").getSchema());
            assertEquals("secure:Hprose中文🙂", remote.echo("Hprose中文🙂"));
        }
    }
}
