/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.direct;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;

/** 在已有多协议配置上启用安全传输，证书由调用者提供，配置和上下文仍由调用者管理。 */
public final class SecurityExample {
    private SecurityExample() {
    }

    /** 服务端提供身份；双向认证时还需信任客户端的证书颁发者。 */
    public static void server(MetConfiguration configuration, String keyStore, String keyPassword, //
            String trustStore, String trustPassword, boolean requireClientCertificate) {
        for (ConnectorSettings connector : configuration.getConnectors()) {
            // TLS 属于物理传输配置，同一监听上的各协议共用证书与认证方式。
            connector.option("tlsEnabled", true) //
                    .option("tlsKeyStore", keyStore) //
                    .option("tlsKeyStorePassword", keyPassword) //
                    .option("tlsClientAuth", requireClientCertificate ? "REQUIRE" : "NONE");
            if (trustStore != null) {
                connector.option("tlsTrustStore", trustStore) //
                        .option("tlsTrustStorePassword", trustPassword);
            }

            // 同一个 HTTPS 端口由 ALPN 选择 HTTP/1.1 或 HTTP/2，协议挂载保持原样。
            if (StringUtils.equals("http", connector.getType())) {
                connector.option("httpScheme", "https").option("tlsAlpn", "h2,http/1.1");
            }
        }
    }

    /** 客户端始终校验服务端；只有双向认证时才提供自己的身份。 */
    public static void client(MetConfiguration configuration, String trustStore, String trustPassword, //
            String keyStore, String keyPassword) {
        for (ConnectorSettings connector : configuration.getConnectors()) {
            // 出站连接使用所属端点的信任库，不修改 JVM 全局 SSL 设置。
            connector.option("tlsEnabled", true) //
                    .option("tlsTrustStore", trustStore) //
                    .option("tlsTrustStorePassword", trustPassword);
            if (keyStore != null) {
                connector.option("tlsKeyStore", keyStore).option("tlsKeyStorePassword", keyPassword);
            }

            // 与现有出站 HTTP 版本保持一致，避免把 HTTP/1.1 连接协商成 HTTP/2。
            if (StringUtils.equals("http", connector.getType())) {
                String version = connector.getOptions().getOrDefault("httpVersion", "1");
                String alpn = "http/1.1";
                if (StringUtils.equals("2", version)) {
                    alpn = "h2";
                }
                connector.option("httpScheme", "https").option("tlsAlpn", alpn);
            }
        }
    }
}
