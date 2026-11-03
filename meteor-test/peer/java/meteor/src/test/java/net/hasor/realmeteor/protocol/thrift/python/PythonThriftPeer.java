/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift.python;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 独占官方 Python SDK 子进程；不共享 Java 连接、编码器或调度线程。 */
final class PythonThriftPeer implements AutoCloseable {
    private final Process               process;
    private final Path                  log;
    private final Thread                reader;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    private PythonThriftPeer(Process process, Path log) {
        this.process = process;
        this.log = log;
        this.reader = new Thread(this::readOutput, "python-thrift-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static PythonThriftPeer provider(String encoding, boolean framed, String security) throws Exception {
        return start("python-thrift", "provider", encoding, framed, security, "0.0.0.0", 0, null);
    }

    static PythonThriftPeer consumer(String encoding, boolean framed, String security, //
            String host, int port, String scenario) throws Exception {
        return start("python-thrift", "consumer", encoding, framed, security, host, port, scenario);
    }

    static PythonThriftPeer versionProvider(String version, String encoding) throws Exception {
        String sdkName = "python-thrift-" + version;
        return start(sdkName, "provider", encoding, true, "plain", "0.0.0.0", 0, null);
    }

    static PythonThriftPeer versionConsumer(String version, String encoding, String host, int port) throws Exception {
        String sdkName = "python-thrift-" + version;
        return start(sdkName, "consumer", encoding, true, "plain", host, port, "version");
    }

    int awaitPort() throws InterruptedException {
        return Integer.parseInt(this.awaitLine("READY:", 10));
    }

    String host() {
        return ContainerProcess.host(this.process);
    }

    long processId() {
        return this.process.pid();
    }

    void requireSuccess() throws InterruptedException {
        // 结果标记只证明业务断言通过；退出码还须证明独立 SDK 没有后台失败。
        String result = this.awaitLine("RESULT:", 15);
        if (!StringUtils.equals(result, "OK")) {
            throw this.failure("Unexpected Python result: " + result);
        }
        if (!this.process.waitFor(5, TimeUnit.SECONDS) || this.process.exitValue() != 0) {
            throw this.failure("Python Thrift consumer did not exit successfully");
        }
    }

    private static PythonThriftPeer start(String sdkName, String mode, String encoding, boolean framed, //
            String security, String host, int port, String scenario) throws Exception {
        // 日志及 SDK 运行目录均属于测试 build，不安装全局 Python 依赖。
        Path directory = Path.of(System.getProperty("python.thrift.logs", "build/python-thrift-peer-logs"));
        Files.createDirectories(directory);
        Path log = Files.createTempFile(directory, sdkName + "-" + mode + "-" + encoding + "-", ".log");
        List<String> command = new ArrayList<>();
        Path peers = Path.of(System.getProperty("meteor.test.peerDirectory", "peer"));
        Path script = peers.resolve("python/thrift/peer.py");
        command.add("/opt/meteor-peer/" + sdkName + "/bin/python");
        command.add(System.getProperty("python.thrift.script", script.toString()));
        command.add("--mode=" + mode);
        command.add("--protocol=" + encoding);
        command.add("--framed=" + framed);
        command.add("--security=" + security);
        command.add("--host=" + host);
        command.add("--port=" + port);
        if (scenario != null) {
            command.add("--scenario=" + scenario);
        }
        if (!StringUtils.equals(sdkName, "python-thrift")) {
            command.add("--sdk-version=" + sdkName.substring("python-thrift-".length()));
        }

        // Python 使用同一套测试 CA 和身份，PEM 从已有 PKCS12 导出，不增加另一套证书。
        if (!StringUtils.equals(security, "plain")) {
            Path certificates = Files.createTempDirectory(directory, "certificates-");
            command.add("--trust=" + exportTrust(certificates));
            boolean provider = StringUtils.equals(mode, "provider");
            if (provider || StringUtils.equals(security, "mutual")) {
                String identity = provider ? "server" : "client";
                exportIdentity(identity, certificates);
                command.add("--certificate=" + certificates.resolve("certificate.pem"));
                command.add("--key=" + certificates.resolve("key.pem"));
            }
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = ContainerProcess.start("python", builder);
        return new PythonThriftPeer(process, log);
    }

    private static Path exportTrust(Path directory) throws Exception {
        KeyStore store = loadStore("trust");
        StringBuilder certificates = new StringBuilder();
        for (String alias : Collections.list(store.aliases())) {
            certificates.append(pem("CERTIFICATE", store.getCertificate(alias).getEncoded()));
        }
        Path target = directory.resolve("trust.pem");
        Files.writeString(target, certificates, StandardCharsets.US_ASCII);
        return target;
    }

    private static void exportIdentity(String identity, Path directory) throws Exception {
        KeyStore store = loadStore(identity);
        for (String alias : Collections.list(store.aliases())) {
            if (store.isKeyEntry(alias)) {
                PrivateKey key = (PrivateKey) store.getKey(alias, "meteor-test".toCharArray());
                StringBuilder chain = new StringBuilder();
                for (Certificate certificate : store.getCertificateChain(alias)) {
                    chain.append(pem("CERTIFICATE", certificate.getEncoded()));
                }
                Files.writeString(directory.resolve("certificate.pem"), chain, StandardCharsets.US_ASCII);
                String encodedKey = pem("PRIVATE KEY", key.getEncoded());
                Files.writeString(directory.resolve("key.pem"), encodedKey, StandardCharsets.US_ASCII);
                return;
            }
        }
        throw new IllegalArgumentException("No private key in test identity: " + identity);
    }

    private static KeyStore loadStore(String identity) throws Exception {
        String resource = "/certs/acceptance/" + identity + ".p12";
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = PythonThriftPeer.class.getResourceAsStream(resource)) {
            store.load(input, "meteor-test".toCharArray());
        }
        return store;
    }

    private static String pem(String label, byte[] encoded) {
        Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[] { '\n' });
        return "-----BEGIN " + label + "-----\n" + encoder.encodeToString(encoded) //
                + "\n-----END " + label + "-----\n";
    }

    private String awaitLine(String prefix, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw this.failure("Python Thrift peer timed out waiting for " + prefix);
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw this.failure("Python Thrift peer exited before " + prefix);
            }
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
    }

    private AssertionError failure(String message) {
        return new AssertionError(message + "; peer log: " + this.log);
    }

    private void readOutput() {
        // 持续排空并落盘对端输出；就绪通知和结果由独立队列交付给测试线程。
        InputStreamReader input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(input); //
             BufferedWriter output = Files.newBufferedWriter(this.log, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.write(line);
                output.newLine();
                output.flush();
                this.lines.offer(line);
            }
        } catch (IOException failure) {
            this.lines.offer("ERROR:" + failure);
        } finally {
            this.lines.offer("");
        }
    }

    @Override
    public void close() throws Exception {
        // 创建者关闭 stdin 通知 Python listener 停止，再回收自己创建的进程及读取线程。
        try {
            this.process.getOutputStream().close();
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                throw this.failure("Python Thrift peer did not close");
            }
            this.reader.join(1000);
            if (this.process.exitValue() != 0) {
                throw this.failure("Python Thrift peer failed");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        } finally {
            if (this.process.isAlive()) {
                this.process.destroyForcibly();
                this.process.waitFor(3, TimeUnit.SECONDS);
            }
            this.reader.join(1000);
        }
    }
}
