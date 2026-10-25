/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.soap;
import java.io.ObjectInputStream;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** 原生对端的 JDK 控制数据与测试证书装载；不依赖 Meteor。 */
final class SoapPeerFiles {
    private SoapPeerFiles() {
    }

    static void requireIsolatedClasspath() {
        // 标准测试输出可以复用，内核、传输库和工具库均不能进入原生运行依赖。
        String[] forbidden = {
                "net.hasor.meteor.MetContext", "net.hasor.neta.NetManager", "net.hasor.cobble.StringUtils"
        };
        for (String name : forbidden) {
            try {
                Class.forName(name, false, SoapPeerFiles.class.getClassLoader());
            } catch (ClassNotFoundException expected) {
                continue;
            }
            throw new IllegalStateException("Official SOAP peer must not load " + name);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path file) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(file))) {
            return (Map<String, Object>) input.readObject();
        }
    }

    static void write(Path file, Map<String, Object> result) throws Exception {
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(file))) {
            output.writeObject(result);
        }
        System.out.println("RESULT completed");
    }

    static SSLContext sslContext(Path directory, String identity) throws Exception {
        // 身份与信任库的位置由宿主显式传入，不依赖宿主资源类路径。
        char[] password = "meteor-test".toCharArray();
        KeyStore trustStore = loadStore(directory.resolve("trust.p12"), password);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        KeyStore keyStore = loadStore(directory.resolve(identity + ".p12"), password);
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private static KeyStore loadStore(Path file, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(file)) {
            store.load(input, password);
        }
        return store;
    }
}
