/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.rpc.rsfs;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;

/** 可直接用于应用配置的 TLS 材料选项；不创建上下文、服务或连接。 */
public final class RsfsConfigurations {
    private RsfsConfigurations() {
    }

    public static MetConfiguration server(String key, String clientAuth) {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("tcp").bind("127.0.0.1", 0);
        connector.option("tlsEnabled", true);
        connector.option("tlsKeyStore", "classpath:/tls/acceptance/" + key + ".p12");
        connector.option("tlsKeyStorePassword", "meteor-test");
        connector.option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12");
        connector.option("tlsTrustStorePassword", "meteor-test");
        connector.option("tlsClientAuth", clientAuth);
        connector.protocol("rsf").addressScheme("rsfs");
        return configuration;
    }

    public static MetConfiguration client(String key, String trust) {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("tcp");
        connector.option("tlsEnabled", true);
        connector.option("tlsTrustStore", "classpath:/tls/acceptance/" + trust + ".p12");
        connector.option("tlsTrustStorePassword", "meteor-test");
        if (StringUtils.isNotBlank(key)) {
            connector.option("tlsKeyStore", "classpath:/tls/acceptance/" + key + ".p12");
            connector.option("tlsKeyStorePassword", "meteor-test");
        }
        connector.protocol("rsf").addressScheme("rsfs");
        return configuration;
    }
}
