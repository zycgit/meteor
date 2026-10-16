/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap.support;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.bootstrap.MetConfiguration;

/** SOAP HTTPS 场景共用的公开配置片段和外部客户端证书装载。 */
public final class SoapTlsSupport {
    private static final String PASSWORD = "meteor-test";
    private static final String ROOT     = "classpath:/tls/acceptance/";

    private SoapTlsSupport() {
    }

    public static MetConfiguration.ConnectorSettings server(MetConfiguration configuration, String alpn, //
            boolean requireClientCertificate) {
        // 服务端只通过正式 Connector 配置开启 TLS，不安装测试专用传输管线。
        String clientAuth = requireClientCertificate ? "REQUIRE" : "NONE";
        return configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("tlsKeyStore", ROOT + "server.p12") //
                .option("tlsKeyStorePassword", PASSWORD) //
                .option("tlsTrustStore", ROOT + "trust.p12") //
                .option("tlsTrustStorePassword", PASSWORD) //
                .option("tlsClientAuth", clientAuth) //
                .option("tlsAlpn", alpn);
    }

    public static MetConfiguration.ConnectorSettings client(MetConfiguration configuration, int httpVersion, //
            String alpn, boolean presentClientCertificate) {
        // 客户端始终信任测试 CA；双向认证场景才装载独立客户端身份。
        MetConfiguration.ConnectorSettings connector = configuration.connector("http") //
                .option("httpVersion", httpVersion) //
                .option("tlsEnabled", true) //
                .option("tlsTrustStore", ROOT + "trust.p12") //
                .option("tlsTrustStorePassword", PASSWORD) //
                .option("tlsAlpn", alpn);
        if (presentClientCertificate) {
            connector.option("tlsKeyStore", ROOT + "client.p12") //
                    .option("tlsKeyStorePassword", PASSWORD);
        }
        return connector;
    }

    public static SSLContext sslContext(String identity, String trustStore) throws Exception {
        // JDK 与 Metro 对端独立加载信任库，可选身份只用于双向认证。
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(loadStore(trustStore));
        KeyManager[] keyManagers = null;
        if (StringUtils.isNotBlank(identity)) {
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(loadStore(identity), PASSWORD.toCharArray());
            keyManagers = keys.getKeyManagers();
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, trust.getTrustManagers(), null);
        return context;
    }

    private static KeyStore loadStore(String name) throws Exception {
        String resource = "/tls/acceptance/" + name + ".p12";
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = SoapTlsSupport.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing TLS acceptance resource: " + resource);
            }
            store.load(input, PASSWORD.toCharArray());
        }
        return store;
    }
}
