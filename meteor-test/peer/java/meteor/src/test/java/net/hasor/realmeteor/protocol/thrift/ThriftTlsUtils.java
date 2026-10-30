/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;

/** Thrift 安全场景共用的无资源证书配置。 */
final class ThriftTlsUtils {
    private ThriftTlsUtils() {
    }

    static void serverTls(ConnectorSettings connector, String identity, String clientAuth, String protocols) {
        connector.option("tlsEnabled", true) //
                .option("tlsKeyStore", "classpath:/certs/acceptance/" + identity + ".p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsClientAuth", clientAuth) //
                .option("tlsProtocols", protocols);
    }

    static void clientTls(ConnectorSettings connector, String identity, String trust, String protocols) {
        connector.option("tlsEnabled", true) //
                .option("tlsTrustStore", "classpath:/certs/acceptance/" + trust + ".p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsProtocols", protocols);
        if (identity != null) {
            connector.option("tlsKeyStore", "classpath:/certs/acceptance/" + identity + ".p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
        }
    }

    static void https(ConnectorSettings connector, int httpVersion) {
        connector.option("httpScheme", "https") //
                .option("tlsAlpn", httpVersion == 2 ? "h2" : "http/1.1");
    }
}
