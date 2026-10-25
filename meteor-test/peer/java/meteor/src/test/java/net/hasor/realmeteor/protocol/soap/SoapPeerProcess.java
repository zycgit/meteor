/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import javax.xml.namespace.QName;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** Metro 与 Axis 独立进程的生命周期及本地控制；场景仍使用真实 SOAP 网络。 */
final class SoapPeerProcess implements AutoCloseable {
    private final Process               process;
    private final Path                  log;
    private final Thread                reader;
    private final BlockingQueue<String> lines  = new LinkedBlockingQueue<>();
    private final StringBuffer          output = new StringBuffer();
    private       int                   port;

    private SoapPeerProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
        this.reader = new Thread(this::readOutput, "soap-peer-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static SoapPeerProcess metroServer(String profile, String version, boolean mutualTls) throws Exception {
        String tls = mutualTls ? System.getProperty("soap.peer.tls.directory") : "";
        return server("metro", List.of("server", profile, version, tls));
    }

    static SoapPeerProcess axisServer() throws Exception {
        return server("axis", List.of("server"));
    }

    private static SoapPeerProcess server(String sdk, List<String> arguments) throws Exception {
        // READY 只在真实官方监听建立后输出，启动失败必须回收已创建的子进程。
        SoapPeerProcess peer = start(sdk, arguments);
        try {
            peer.port = peer.awaitPort();
            return peer;
        } catch (Throwable failure) {
            peer.close();
            throw failure;
        }
    }

    static Map<String, Object> dispatch(URL wsdl, QName service, QName port, String action) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operation", "dispatch");
        request.put("wsdl", wsdl.toExternalForm());
        request.put("service", service);
        request.put("port", port);
        request.put("soapAction", action);
        return request;
    }

    static Map<String, Object> invokeMetro(Map<String, Object> options, SOAPMessage message) throws Exception {
        // SAAJ 在宿主只构造/读取 XML；真正的 Service、Dispatch 和网络调用属于独立 SDK。
        Map<String, Object> request = messageRequest(options, message);
        return metro(request);
    }

    static void invokeMetroOneWay(Map<String, Object> options, SOAPMessage message) throws Exception {
        Map<String, Object> request = messageRequest(options, message);
        request.put("oneWay", true);
        metro(request);
    }

    static SOAPMessage response(Map<String, Object> result) throws Exception {
        String protocol = (String) result.get("soapProtocol");
        byte[] body = (byte[]) result.get("response");
        return MessageFactory.newInstance(protocol).createMessage(null, new ByteArrayInputStream(body));
    }

    static Map<String, Object> metro(Map<String, Object> request) throws Exception {
        return exchange("metro", request);
    }

    static Map<String, Object> axis(Map<String, Object> request) throws Exception {
        return exchange("axis", request);
    }

    static String tlsDirectory() {
        return System.getProperty("soap.peer.tls.directory");
    }

    private static Map<String, Object> messageRequest(Map<String, Object> options, SOAPMessage message) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(options);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        String namespace = message.getSOAPPart().getEnvelope().getNamespaceURI();
        String protocol = SOAPConstants.SOAP_1_1_PROTOCOL;
        if (StringUtils.equals(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, namespace)) {
            protocol = SOAPConstants.SOAP_1_2_PROTOCOL;
        }
        request.put("soapProtocol", protocol);
        request.put("request", bytes.toByteArray());
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> exchange(String sdk, Map<String, Object> request) throws Exception {
        // 控制文件只包含 JDK Map、QName、列表和 XML 字节，不承载 SDK 或 Meteor Bean。
        Path directory = Files.createTempDirectory(outputDirectory(), sdk + "-operation-");
        Path input = directory.resolve("request.bin");
        Path output = directory.resolve("response.bin");
        try (ObjectOutputStream stream = new ObjectOutputStream(Files.newOutputStream(input))) {
            stream.writeObject(request);
        }
        try (SoapPeerProcess peer = start(sdk, List.of("client", input.toString(), output.toString()))) {
            peer.requireCompletion();
            try (ObjectInputStream stream = new ObjectInputStream(Files.newInputStream(output))) {
                return (Map<String, Object>) stream.readObject();
            }
        }
    }

    String host() {
        return ContainerProcess.host(this.process);
    }

    int getPort() {
        return this.port;
    }

    long processId() {
        return this.process.pid();
    }

    private static SoapPeerProcess start(String sdk, List<String> arguments) throws IOException {
        String classpath = System.getProperty("soap." + sdk + ".peer.classpath");
        if (StringUtils.isBlank(classpath)) {
            throw new IllegalStateException("Official SOAP peer classpath is not configured");
        }
        Path log = Files.createTempFile(outputDirectory(), "peer-", ".log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(classpath);
        command.add(switch (sdk) {
            case "metro" -> "net.hasor.peer.protocol.soap.MetroPeer";
            case "axis" -> "net.hasor.peer.protocol.soap.AxisPeer";
            default -> throw new IllegalArgumentException("Unknown SOAP peer SDK: " + sdk);
        });
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = ContainerProcess.start("java", builder);
        return new SoapPeerProcess(process, log);
    }

    private static Path outputDirectory() throws IOException {
        String configured = System.getProperty("soap.peer.output.directory", "build/reports/soap-peer");
        Path directory = Path.of(configured).toAbsolutePath();
        Files.createDirectories(directory);
        return directory;
    }

    private int awaitPort() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw this.failure("Official SOAP server startup timed out");
            }
            String line = this.lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null || line.isEmpty()) {
                throw this.failure("Official SOAP server exited before listening");
            }
            if (line.startsWith("READY ")) {
                return Integer.parseInt(line.substring(6));
            }
        }
    }

    private void requireCompletion() throws InterruptedException {
        if (!this.process.waitFor(15, TimeUnit.SECONDS)) {
            throw this.failure("Official SOAP operation timed out");
        }
        this.reader.join(1000);
        boolean completed = this.output.toString().contains("RESULT completed\n");
        if (this.process.exitValue() != 0 || !completed) {
            throw this.failure("Official SOAP operation did not complete");
        }
    }

    private AssertionError failure(String message) {
        return new AssertionError(message + "; native log: " + this.log + "\n" + this.output);
    }

    private void readOutput() {
        // 持续排空原生对端日志，不能让子进程因输出缓冲区满而阻塞。
        InputStreamReader input = new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader stream = new BufferedReader(input)) {
            String line;
            while ((line = stream.readLine()) != null) {
                this.output.append(line).append('\n');
                Files.writeString(this.log, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                this.lines.offer(line);
            }
        } catch (IOException failure) {
            this.output.append(failure).append('\n');
        } finally {
            this.lines.offer("");
        }
    }

    @Override
    public void close() throws Exception {
        // 正常关闭通过 stdin EOF 通知服务端；失败时也回收本对象创建的进程。
        try {
            if (this.process.isAlive()) {
                this.process.getOutputStream().close();
            }
        } finally {
            if (!this.process.waitFor(3, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
                this.process.waitFor(3, TimeUnit.SECONDS);
            }
            this.reader.join(1000);
        }
    }
}
