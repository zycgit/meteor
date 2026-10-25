/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.composition.http;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;

/** 组合场景自己的证书与无资源配置，不改变 JVM 全局 TLS 状态。 */
final class HttpCompositionTlsUtils {
    private HttpCompositionTlsUtils() {
    }

    static void server(MetConfiguration configuration, String alpn, String clientAuth) {
        ConnectorSettings connector = configuration.getConnectors().get(0);
        connector.option("tlsEnabled", true).option("httpScheme", "https") //
                .option("tlsKeyStore", "classpath:/example/composition/socket/tls/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/example/composition/socket/tls/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsClientAuth", clientAuth).option("tlsAlpn", alpn);
    }

    static void client(MetConfiguration configuration, String alpn, String identity) {
        ConnectorSettings connector = configuration.getConnectors().get(0);
        connector.option("tlsEnabled", true).option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/example/composition/socket/tls/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test").option("tlsAlpn", alpn);
        if (identity != null) {
            connector.option("tlsKeyStore", "classpath:/example/composition/socket/tls/" + identity + ".p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
        }
    }

    static SSLContext clientContext(String identity) throws Exception {
        // 独立 JDK 客户端使用相同测试 CA，双向认证时提供自己的客户端证书。
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(load("trust"));
        KeyManagerFactory keys = null;
        if (identity != null) {
            keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(load(identity), "meteor-test".toCharArray());
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys == null ? null : keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private static KeyStore load(String name) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        String resource = "/example/composition/socket/tls/" + name + ".p12";
        try (InputStream input = HttpCompositionTlsUtils.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing composition certificate: " + resource);
            }
            store.load(input, "meteor-test".toCharArray());
        }
        return store;
    }
}
