/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.grpc;
import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.TransportPipeline;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslDuplex;

/** 示例 TLS 管线由 transportPipeline 加载；内置证书仅用于 localhost 演示。 */
public final class DemoTlsPipeline implements TransportPipeline {
    public void initialize(TransportConfig config, ProtoBuildContext pipeline) throws Exception {
        // 应用负责准备证书和信任库，再将 TLS 处理器加入传输管线。
        KeyStore store = KeyStore.getInstance("PKCS12");
        char[] password = "meteor-test".toCharArray();
        try (InputStream certificate = DemoTlsPipeline.class.getResourceAsStream("/tls/localhost.p12")) {
            store.load(certificate, password);
        }

        // 同一测试证书同时用作本地身份和对端信任证书。
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);

        // 配置 ALPN；客户端还要启用对 localhost 的主机名校验。
        SslConfig ssl = new SslConfig();
        ssl.setSslContext(context);
        String protocols = config.option("tlsAlpn", "h2,http/1.1");
        ssl.setAppProtocol(protocols.split(","));
        if (pipeline.getChannel().isClient()) {
            ssl.setPeerHost("localhost");
            ssl.setEndpointAlgorithm("HTTPS");
        }
        pipeline.addLast("tls", new SslDuplex(ssl));
    }
}
