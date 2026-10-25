/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.dubbo;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.io.FileUtils;

/** 原生 SDK 发布网卡地址，证书在场景内覆盖本机实际地址，关闭后删除全部生成文件。 */
final class DubboRegistryServerCertificate implements AutoCloseable {
    private final Path directory;

    private DubboRegistryServerCertificate(Path directory) {
        this.directory = directory;
    }

    static DubboRegistryServerCertificate create() throws Exception {
        Path directory = Files.createTempDirectory("meteor-dubbo-registry-server-");
        DubboRegistryServerCertificate certificate = new DubboRegistryServerCertificate(directory);
        try {
            certificate.generate();
            return certificate;
        } catch (Exception failure) {
            try {
                certificate.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    String certificate() {
        return this.directory.resolve("server.pem").toString();
    }

    String privateKey() {
        return this.directory.resolve("server-key.pem").toString();
    }

    String trustStore() {
        return this.directory.resolve("trust.p12").toString();
    }

    private void generate() throws Exception {
        // SAN 同时保留回环和实际网卡 IP，主机名验证仍由真实 TLS 握手执行。
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        String keyStore = this.directory.resolve("server.p12").toString();
        String alternatives = "SAN=" + String.join(",", subjectAlternatives());
        this.run(keytool.toString(), "-genkeypair", "-alias", "server", //
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", //
                "-dname", "CN=localhost", "-ext", alternatives, //
                "-ext", "KU=digitalSignature,keyEncipherment", "-ext", "EKU=serverAuth", //
                "-storetype", "PKCS12", "-keystore", keyStore, //
                "-storepass", "meteor-test", "-keypass", "meteor-test", "-noprompt");

        // 官方 SDK 使用同一证书的 PEM 格式。
        this.run(keytool.toString(), "-exportcert", "-rfc", "-alias", "server", //
                "-keystore", keyStore, "-storepass", "meteor-test", "-file", this.certificate());

        // JDK 从密钥库导出标准 PKCS#8 私钥，不引入额外工具依赖。
        char[] password = "meteor-test".toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream source = Files.newInputStream(Path.of(keyStore))) {
            store.load(source, password);
        }
        Key key = store.getKey("server", password);
        Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[] { '\n' });
        String encoded = encoder.encodeToString(key.getEncoded());
        String privateKey = "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(Path.of(this.privateKey()), privateKey, StandardCharsets.US_ASCII);

        // Meteor 只信任本场景的服务器叶子证书。
        this.run(keytool.toString(), "-importcert", "-alias", "server", "-noprompt", //
                "-file", this.certificate(), "-storetype", "PKCS12", //
                "-keystore", this.trustStore(), "-storepass", "meteor-test");
    }

    private static Set<String> subjectAlternatives() throws IOException {
        Set<String> alternatives = new LinkedHashSet<>();
        alternatives.add("dns:localhost");
        alternatives.add("dns:*.meteor.test");
        alternatives.add("ip:127.0.0.1");
        alternatives.add("ip:::1");
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp()) {
                continue;
            }
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                String address = addresses.nextElement().getHostAddress();
                int zone = address.indexOf('%');
                if (zone >= 0) {
                    address = address.substring(0, zone);
                }
                alternatives.add("ip:" + address);
            }
        }
        return alternatives;
    }

    private void run(String executable, String... arguments) throws Exception {
        // 文件接收子进程输出，避免命令缓冲区阻塞；每一步都使用有界等待。
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(List.of(arguments));
        Path output = this.directory.resolve("command.log");
        Process process = new ProcessBuilder(command) //
                .redirectErrorStream(true) //
                .redirectOutput(output.toFile()) //
                .start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                throw new IOException("Test certificate command timed out: " + executable);
            }
            if (process.exitValue() != 0) {
                String diagnostic = Files.readString(output);
                throw new IOException("Test certificate command failed: " + executable + "\n" + diagnostic);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public void close() throws IOException {
        FileUtils.deleteDirectory(this.directory.toFile());
    }
}
