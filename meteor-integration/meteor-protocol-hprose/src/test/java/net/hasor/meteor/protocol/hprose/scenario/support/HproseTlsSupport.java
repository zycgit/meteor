/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.scenario.support;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** 仅为独立 JDK HTTPS 对端加载证书；Meteor 使用正式 connector TLS 配置。 */
public final class HproseTlsSupport {
    public static SSLContext trustedClientContext() throws Exception {
        // 独立客户端只信任测试 CA，不装载身份或绕过服务器证书校验。
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(loadStore("trust"));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    private static KeyStore loadStore(String name) throws Exception {
        String resource = "/tls/acceptance/" + name + ".p12";
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = HproseTlsSupport.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing TLS test resource: " + resource);
            }
            store.load(input, "meteor-test".toCharArray());
        }
        return store;
    }
}
