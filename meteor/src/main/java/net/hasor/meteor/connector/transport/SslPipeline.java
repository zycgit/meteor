/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.*;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.codec.ssl.SslClientAuth;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslDuplex;

/** Shared certificate material and Neta {@link SslDuplex} assembly for secure socket transports. */
public class SslPipeline {
    private final SSLContext    context;
    private final SslClientAuth clientAuth;
    private final String[]      protocols;
    private final String[]      applicationProtocols;
    private final String        securityName;

    protected SslPipeline(TransportConfig config, ClassLoader loader, String contextProtocol, //
            String defaultProtocols, String securityName) {
        this.securityName = securityName;
        try {
            String keyStore = config.option("tlsKeyStore", "");
            if (config.bindEnabled() && StringUtils.isBlank(keyStore)) {
                throw new IllegalArgumentException(securityName + " listener requires tlsKeyStore");
            }

            // 证书和信任材料在端点初始化时读取；不改变 JVM 的全局安全配置。
            KeyManager[] keys = null;
            if (StringUtils.isNotBlank(keyStore)) {
                String storePassword = config.option("tlsKeyStorePassword", "");
                char[] password = storePassword.toCharArray();
                KeyStore store = loadStore(keyStore, config.option("tlsKeyStoreType", "PKCS12"), password, loader);
                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                char[] keyPassword = config.option("tlsKeyPassword", storePassword).toCharArray();
                factory.init(store, keyPassword);
                keys = factory.getKeyManagers();
            }

            TrustManager[] trust = null;
            String trustStore = config.option("tlsTrustStore", "");
            if (StringUtils.isNotBlank(trustStore)) {
                char[] password = config.option("tlsTrustStorePassword", "").toCharArray();
                String storeType = config.option("tlsTrustStoreType", "PKCS12");
                KeyStore store = loadStore(trustStore, storeType, password, loader);
                String algorithm = TrustManagerFactory.getDefaultAlgorithm();
                TrustManagerFactory factory = TrustManagerFactory.getInstance(algorithm);
                factory.init(store);
                trust = factory.getTrustManagers();
            }

            // 一个端点共享 SSLContext，每条 Neta 通道仍创建自己的 SSLEngine。
            this.context = SSLContext.getInstance(contextProtocol);
            this.context.init(keys, trust, null);
            this.clientAuth = SslClientAuth.valueOf(config.option("tlsClientAuth", "NONE").toUpperCase(Locale.ROOT));
            this.protocols = split(config.option("tlsProtocols", defaultProtocols), securityName);
            String[] supportedProtocols = this.context.getSupportedSSLParameters().getProtocols();
            Set<String> supported = new HashSet<>(Arrays.asList(supportedProtocols));
            for (String protocol : this.protocols) {
                if (!supported.contains(protocol)) {
                    throw new IllegalArgumentException("Unsupported " + securityName + " protocol: " + protocol);
                }
            }

            String alpn = config.option("tlsAlpn", "");
            this.applicationProtocols = StringUtils.isBlank(alpn) ? null : split(alpn, securityName);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            String message = "Cannot initialize " + securityName + " for endpoint " + config.name();
            throw new IllegalArgumentException(message, failure);
        }
    }

    static SslPipeline newTls(TransportConfig config, ClassLoader loader) {
        return new SslPipeline(config, loader, "TLS", "TLSv1.3,TLSv1.2", "TLS");
    }

    final void initialize(ProtoBuildContext stack, String peerHost) {
        SslConfig ssl = new SslConfig();
        ssl.setSslContext(this.context);
        ssl.setClientAuth(this.clientAuth);
        ssl.setProtocols(this.protocols.clone());
        if (this.applicationProtocols != null) {
            ssl.setAppProtocol(this.applicationProtocols.clone());
        }
        if (stack.getChannel().isClient()) {
            // 身份验证使用调用地址的主机名，不允许通过另一个配置值替换目标身份。
            ssl.setPeerHost(peerHost);
            ssl.setEndpointAlgorithm("HTTPS");
        }
        stack.addLast(this.securityName.toLowerCase(Locale.ROOT), this.createDuplex(ssl));
    }

    protected SslDuplex createDuplex(SslConfig config) {
        return new SslDuplex(config);
    }

    private static String[] split(String value, String securityName) {
        String[] values = value.split(",", -1);
        for (int index = 0; index < values.length; index++) {
            values[index] = values[index].trim();
            if (StringUtils.isBlank(values[index])) {
                throw new IllegalArgumentException(securityName + " protocol names must not be empty");
            }
        }
        return values;
    }

    private static KeyStore loadStore(String location, String type, char[] password, //
            ClassLoader loader) throws Exception {
        InputStream source;
        if (location.startsWith("classpath:")) {
            String name = location.substring("classpath:".length());
            while (name.startsWith("/")) {
                name = name.substring(1);
            }
            source = loader.getResourceAsStream(name);
            if (source == null) {
                throw new IOException("TLS store resource not found: " + location);
            }
        } else {
            source = Files.newInputStream(Path.of(location));
        }
        try (InputStream stream = source) {
            KeyStore store = KeyStore.getInstance(type);
            store.load(stream, password);
            return store;
        }
    }
}
