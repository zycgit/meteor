/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;

/** ONC RPC 安全场景的证书配置；不持有上下文或连接。 */
final class OncRpcTlsUtils {
    private OncRpcTlsUtils() {
    }

    static void server(ConnectorSettings connector, String identity, String clientAuth, String protocols) {
        connector.option("tlsEnabled", true) //
                .option("tlsKeyStore", "classpath:/oncrpc/tls/" + identity + ".p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/oncrpc/tls/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsClientAuth", clientAuth) //
                .option("tlsProtocols", protocols);
    }

    static void client(ConnectorSettings connector, String identity, String trust, String protocols) {
        connector.option("tlsEnabled", true) //
                .option("tlsTrustStore", "classpath:/oncrpc/tls/" + trust + ".p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsProtocols", protocols);
        if (identity != null) {
            connector.option("tlsKeyStore", "classpath:/oncrpc/tls/" + identity + ".p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
        }
    }
}
