/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.rpc.websocket;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.bootstrap.MetConfiguration.ProtocolSettings;

/** 可复用的无资源配置；上下文创建、发布与调用仍在每个场景中可见。 */
public final class WebSocketConfigurations {
    private WebSocketConfigurations() {
    }

    public static ConnectorSettings http(MetConfiguration configuration, int version) {
        configuration.setDefaultTimeout(10000).setQueueMinPoolSize(4);
        return configuration.connector("http").option("httpVersion", version);
    }

    public static ProtocolSettings websocket(ConnectorSettings connector, int version, boolean binary) {
        return connector.protocol("jsonws") //
                .contextPath("/ws") //
                .option("upgrade", "websocket") //
                .option("httpVersion", version) //
                .option("binary", binary);
    }

    public static void tlsServer(ConnectorSettings connector, int version, String clientAuth) {
        connector.option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsClientAuth", clientAuth) //
                .option("tlsAlpn", version == 2 ? "h2" : "http/1.1");
    }

    public static void tlsClient(ConnectorSettings connector, int version, String key) {
        connector.option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsAlpn", version == 2 ? "h2" : "http/1.1");
        if (StringUtils.isNotBlank(key)) {
            connector.option("tlsKeyStore", "classpath:/tls/acceptance/" + key + ".p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
        }
    }
}
