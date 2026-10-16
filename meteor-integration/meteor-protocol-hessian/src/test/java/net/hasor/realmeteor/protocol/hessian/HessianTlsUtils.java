/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;

/** 无资源的传输配置和独立 JDK 对端的测试证书装载。 */
final class HessianTlsUtils {
    private HessianTlsUtils() {
    }

    static void serverTls(ConnectorSettings connector, int version, String clientAuth) {
        connector.option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsClientAuth", clientAuth) //
                .option("tlsAlpn", version == 2 ? "h2" : "http/1.1");
    }

    static void clientTls(ConnectorSettings connector, int version, String identity) {
        connector.option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsAlpn", version == 2 ? "h2" : "http/1.1");
        if (identity != null) {
            connector.option("tlsKeyStore", "classpath:/tls/acceptance/" + identity + ".p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
        }
    }

    static SSLContext clientContext(String trustStore, String identity) throws Exception {
        // 外部客户端严格验证指定 CA；双向认证时再加载独立的客户端身份。
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(loadStore(trustStore));
        KeyManagerFactory keys = null;
        if (identity != null) {
            keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(loadStore(identity), "meteor-test".toCharArray());
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys == null ? null : keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private static KeyStore loadStore(String name) throws Exception {
        String resource = "/tls/acceptance/" + name + ".p12";
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = HessianTlsUtils.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing TLS test resource: " + resource);
            }
            store.load(input, "meteor-test".toCharArray());
        }
        return store;
    }
}
