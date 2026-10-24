/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift.python;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 官方 Python 对端的编码、TCP 分帧和认证参数，仅组装公开配置。 */
final class PythonThriftSettings {
    private PythonThriftSettings() {
    }

    static Collection<Object[]> parameters() {
        // 每个组合形成独立的 JUnit 实例，四种业务行为由独立方法执行。
        List<Object[]> parameters = new ArrayList<>();
        for (String encoding : List.of("binary", "compact", "json")) {
            for (boolean framed : List.of(false, true)) {
                for (String security : List.of("plain", "tls", "mutual")) {
                    parameters.add(new Object[] { encoding, framed, security });
                }
            }
        }
        return parameters;
    }

    static MetConfiguration configuration(String encoding, boolean framed, String security, boolean listener) {
        // 传输配置负责监听及 TLS；协议只接收自己的编码和分帧参数。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.setQueueMinPoolSize(2).setQueueMaxPoolSize(4).setDefaultTimeout(5000);
        ConnectorSettings tcp = configuration.connector("tcp");
        if (listener) {
            tcp.bind(ContainerProcess.runnerHost(), 0);
        }
        tcp.protocol("thrift").addressScheme(scheme(security)) //
                .option("encoding", encoding).option("framed", framed);
        if (StringUtils.equals(security, "plain")) {
            return configuration;
        }

        // 每个安全方向均校验 CA，双向认证另外配置客户端身份。
        tcp.option("tlsEnabled", true) //
                .option("tlsTrustStore", "classpath:/thrift/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test");
        if (listener) {
            tcp.option("tlsKeyStore", "classpath:/thrift/tls/acceptance/server.p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
            String clientAuth = StringUtils.equals(security, "mutual") ? "REQUIRE" : "NONE";
            tcp.option("tlsClientAuth", clientAuth);
        } else if (StringUtils.equals(security, "mutual")) {
            tcp.option("tlsKeyStore", "classpath:/thrift/tls/acceptance/client.p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
        }
        return configuration;
    }

    static String scheme(String security) {
        return StringUtils.equals(security, "plain") ? "thrift" : "thrifts";
    }
}
