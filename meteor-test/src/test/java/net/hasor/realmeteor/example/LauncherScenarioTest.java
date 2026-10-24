/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example;
import java.nio.charset.StandardCharsets;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.address.InterAddress;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 直接启动用户的 Main，提供者和消费者只使用生产依赖，在独立容器中通信。 */
public class LauncherScenarioTest {
    private static final String DIRECT    = "net.hasor.example.direct.";
    private static final String DISCOVERY = "net.hasor.example.discovery.";

    @Rule
    public final TemporaryFolder files = new TemporaryFolder();

    @Test(timeout = 60000)
    public void independentDirectProcessesUseAllElevenProtocols() throws Exception {
        // 端口由提供者分配，外部消费者使用启动日志公布的真实地址。
        Path providerLog = this.files.newFile("provider.log").toPath();
        Process provider = this.launch(DIRECT + "ProviderMain", providerLog, "plain", "server", //
                "0.0.0.0", "0", "0", "plain");
        try {
            String ready = this.awaitReady(provider, providerLog);
            int tcpPort = this.address(ready, "rsf").getPort();
            int httpPort = this.address(ready, "soap").getPort();

            // 独立消费者容器执行全部协议读写，验证用户运行入口而非测试里的替代实现。
            String providerHost = ContainerProcess.host(provider);
            Path consumerLog = this.files.newFile("consumer.log").toPath();
            Process consumer = this.launch(DIRECT + "ConsumerMain", consumerLog, "plain", "client", //
                    "all", providerHost, Integer.toString(tcpPort), Integer.toString(httpPort), "plain");
            String result = this.completed(consumer, consumerLog);
            assertTrue(result, result.contains("rsf final: id=1001, updated by triple"));
        } finally {
            this.stop(provider);
        }
    }

    @Test(timeout = 60000)
    public void independentTlsProcessesUseAllElevenProtocols() throws Exception {
        // 只有提供者配置身份，消费者仅信任测试 CA；两端仍使用原有协议挂载。
        Path providerLog = this.files.newFile("tls-provider.log").toPath();
        Process provider = this.launch(DIRECT + "ProviderMain", providerLog, "tls", "server", //
                "0.0.0.0", "0", "0", "tls");
        try {
            String ready = this.awaitReady(provider, providerLog);
            String tcpPort = Integer.toString(this.address(ready, "rsf").getPort());
            String httpPort = Integer.toString(this.address(ready, "soap").getPort());

            // 独立消费者同时经过 TLS/TCP、HTTPS/1.1 与 HTTPS/2 访问共享订单。
            String providerHost = ContainerProcess.host(provider);
            Path consumerLog = this.files.newFile("tls-consumer.log").toPath();
            Process consumer = this.launch(DIRECT + "ConsumerMain", consumerLog, "tls", "client", //
                    "all", providerHost, tcpPort, httpPort, "tls");
            String result = this.completed(consumer, consumerLog);
            assertTrue(result, result.contains("rsf final: id=1001, updated by triple"));
        } finally {
            this.stop(provider);
        }
    }

    @Test(timeout = 60000)
    public void independentMutualTlsProcessesUseAllElevenProtocols() throws Exception {
        // 两端都提供身份和信任库，验证完整 Main 对双向认证配置的转交。
        Path providerLog = this.files.newFile("mtls-provider.log").toPath();
        Process provider = this.launch(DIRECT + "ProviderMain", providerLog, "mtls", "server", //
                "0.0.0.0", "0", "0", "mtls");
        try {
            String ready = this.awaitReady(provider, providerLog);
            String tcpPort = Integer.toString(this.address(ready, "rsf").getPort());
            String httpPort = Integer.toString(this.address(ready, "soap").getPort());

            // 同一个服务绑定覆盖全部协议，不为安全模式重复发布业务对象。
            String providerHost = ContainerProcess.host(provider);
            Path consumerLog = this.files.newFile("mtls-consumer.log").toPath();
            Process consumer = this.launch(DIRECT + "ConsumerMain", consumerLog, "mtls", "client", //
                    "all", providerHost, tcpPort, httpPort, "mtls");
            String result = this.completed(consumer, consumerLog);
            assertTrue(result, result.contains("rsf final: id=1001, updated by triple"));
        } finally {
            this.stop(provider);
        }
    }

    @Test(timeout = 60000)
    public void independentDiscoveryProcessesFindGrpcThroughZookeeper() throws Exception {
        // 测试启动真实 ZooKeeper，两个业务容器仅通过它交换登记信息。
        try (ZookeeperTestServer registry = new ZookeeperTestServer()) {
            String registryAddress = "zk://" + registry.getConnectString();
            Path providerLog = this.files.newFile("discovery-provider.log").toPath();
            Process provider = this.launch(DISCOVERY + "ProviderMain", providerLog, "plain", "server", //
                    registryAddress, "0.0.0.0", "0", "0");
            try {
                this.awaitReady(provider, providerLog);

                // 消费者参数中没有 RPC 地址或端口，只有注册中心地址、协议和订单 ID。
                Path consumerLog = this.files.newFile("discovery-consumer.log").toPath();
                Process consumer = this.launch(DISCOVERY + "ConsumerMain", consumerLog, "plain", "client", //
                        registryAddress, "grpc", "3001");
                String result = this.completed(consumer, consumerLog);
                assertTrue(result, result.contains("grpc -> order 3001: registry-discovery-grpc"));
                assertTrue(result, result.contains("Discovered addresses:"));
            } finally {
                this.stop(provider);
            }
        }
    }

    private Process launch(String main, Path log, String security, String identity, String... arguments) throws Exception {
        // 类路径来自正式 main runtime，不把本项目测试夹具或 JUnit 传入业务 JVM。
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        command.add("-cp");
        command.add(System.getProperty("meteor.example.mainClasspath"));
        command.add(main);
        command.addAll(List.of(arguments));
        if (StringUtils.equals(main, DISCOVERY + "ProviderMain")) {
            // 容器创建后才确定自身 DNS；仅在入口处补充已有的发布地址环境配置。
            List<String> entrypoint = new ArrayList<>();
            entrypoint.add("/bin/sh");
            entrypoint.add("-c");
            entrypoint.add("export METEOR_PUBLISH_HOST=\"$METEOR_TEST_PEER_HOST\"; exec \"$@\"");
            entrypoint.add("meteor-discovery-provider");
            entrypoint.addAll(command);
            command = entrypoint;
        }
        ProcessBuilder launcher = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        Map<String, String> environment = launcher.environment();
        environment.keySet().removeIf(name -> name.startsWith("METEOR_TLS_") || name.startsWith("METEOR_REGISTRY_"));
        environment.remove("METEOR_PUBLISH_HOST");
        if (!StringUtils.equals(security, "plain")) {
            // 测试证书经共享文件路径传给正式入口，业务 JVM 无需借用测试类路径。
            Path certificates = Path.of("src/test/resources/tls/acceptance").toAbsolutePath();
            environment.put("METEOR_TLS_TRUST_STORE", certificates.resolve("trust.p12").toString());
            environment.put("METEOR_TLS_TRUST_PASSWORD", "meteor-test");
            if (StringUtils.equals(identity, "server") || StringUtils.equals(security, "mtls")) {
                Path keyStore = certificates.resolve(identity + ".p12");
                environment.put("METEOR_TLS_KEY_STORE", keyStore.toString());
                environment.put("METEOR_TLS_KEY_PASSWORD", "meteor-test");
            }
        }
        return ContainerProcess.start("java", launcher);
    }

    private String awaitReady(Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (output.contains("provider is ready")) {
                return output;
            }
            assertTrue(output, process.isAlive());
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("Provider did not become ready: " + Files.readString(log));
    }

    private InterAddress address(String output, String protocol) throws URISyntaxException {
        String prefix = protocol + " -> ";
        for (String line : output.split("\\R")) {
            if (line.startsWith(prefix)) {
                return new InterAddress(line.substring(prefix.length()));
            }
        }
        throw new AssertionError("Missing endpoint: " + protocol + "\n" + output);
    }

    private String completed(Process consumer, Path log) throws Exception {
        try {
            assertTrue("Consumer did not exit: " + log, consumer.waitFor(30, TimeUnit.SECONDS));
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertEquals(output, 0, consumer.exitValue());
            return output;
        } finally {
            this.stop(consumer);
        }
    }

    private void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue("Process did not exit", process.waitFor(5, TimeUnit.SECONDS));
        }
    }
}
