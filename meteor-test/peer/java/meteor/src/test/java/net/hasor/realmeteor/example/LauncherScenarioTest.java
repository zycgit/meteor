/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.storage.NacosTestServer;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** 每个协议分别启动测试自有的提供者和消费者 JVM。 */
@RunWith(Parameterized.class)
public class LauncherScenarioTest {
    @Parameterized.Parameter
    public String protocol;

    @Rule
    public final TemporaryFolder files = new TemporaryFolder();

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> protocols() {
        // 按示例目录逐项登记，每项分别执行直连、Nacos 和 ZooKeeper 注册发现。
        List<Object[]> protocols = new ArrayList<>();
        protocols.add(new Object[] { "rsf" });
        protocols.add(new Object[] { "dubbo" });
        protocols.add(new Object[] { "thrift" });
        protocols.add(new Object[] { "hprose" });
        protocols.add(new Object[] { "soap" });
        protocols.add(new Object[] { "jsonrpc" });
        protocols.add(new Object[] { "xmlrpc" });
        protocols.add(new Object[] { "hessian" });
        protocols.add(new Object[] { "grpc" });
        protocols.add(new Object[] { "grpc-web" });
        protocols.add(new Object[] { "connect" });
        protocols.add(new Object[] { "triple" });
        protocols.add(new Object[] { "oncrpc" });
        return protocols;
    }

    @Test(timeout = 60000)
    public void independentDirectProcessesUseOneProtocol() throws Exception {
        // 提供者绑定独立端口，消费者与它在不同的常驻 Java 容器中运行。
        Path providerLog = this.files.newFile("provider.log").toPath();
        Process provider = this.launch("ProviderMain", providerLog, null);
        try {
            String target = this.awaitReady(provider, providerLog);

            // 消费者运行真实 Main，业务写入和查询通过网络回到当前提供者。
            Path consumerLog = this.files.newFile("consumer.log").toPath();
            Process consumer = this.launch("ConsumerMain", consumerLog, target);
            String output = this.completed(consumer, consumerLog);
            this.assertOrder(output);
        } finally {
            this.stop(provider);
        }
    }

    @Test(timeout = 180000)
    public void independentNacosDiscoveryProcessesUseOneProtocol() throws Exception {
        // Nacos 服务由 worker 复用，当前句柄分配独立数据分组。
        Path serverHome = this.files.newFolder("nacos").toPath();
        try (NacosTestServer registry = new NacosTestServer(serverHome)) {
            registry.start();
            String address = registry.address();
            String group = registry.group();

            // 提供者运行当前协议的公开 Main，登记它实际监听的业务端点。
            Path providerLog = this.files.newFile("nacos-provider.log").toPath();
            Process provider = this.launch("NacosRegistryProviderMain", providerLog, null, address, group);
            try {
                this.awaitReady(provider, providerLog);

                // 消费者原样运行，通过注册中心自动发现提供者并保存、查询订单。
                Path consumerLog = this.files.newFile("nacos-consumer.log").toPath();
                Process consumer = this.launch("NacosRegistryConsumerMain", consumerLog, null, address, group);
                String output = this.completed(consumer, consumerLog);
                this.assertOrder(output);
                assertTrue(output, output.contains("Discovered addresses:"));
            } finally {
                this.stop(provider);
            }
        }
    }

    @Test(timeout = 60000)
    public void independentZookeeperDiscoveryProcessesUseOneProtocol() throws Exception {
        // ZooKeeper 服务由 worker 复用，当前场景的应用共用一个独立 chroot。
        try (ZookeeperTestServer registry = ZookeeperTestServer.shared()) {
            String address = registry.getConnectString();
            // 提供者使用独立的 ZooKeeper 入口，发布当前协议的业务端点。
            Path providerLog = this.files.newFile("zookeeper-provider.log").toPath();
            Process provider = this.launch("ZookeeperRegistryProviderMain", providerLog, null, address);
            try {
                this.awaitReady(provider, providerLog);

                // 消费者使用独立入口自动发现服务，业务写入和查询通过真实网络完成。
                Path consumerLog = this.files.newFile("zookeeper-consumer.log").toPath();
                Process consumer = this.launch("ZookeeperRegistryConsumerMain", consumerLog, null, address);
                String output = this.completed(consumer, consumerLog);
                this.assertOrder(output);
                assertTrue(output, output.contains("Discovered addresses:"));
            } finally {
                this.stop(provider);
            }
        }
    }

    private void assertOrder(String output) throws Exception {
        // 示例直接输出业务 JSON，确认查询得到本次写入的完整订单。
        String json = output.lines().filter(line -> line.startsWith("{")).findFirst() //
                .orElseThrow(() -> new AssertionError("Missing order result: " + output));
        JsonNode order = new ObjectMapper().readTree(json);
        assertEquals(1001, order.path("id").asInt());
        assertEquals("created by " + this.protocol, order.path("description").asText());
        assertEquals("bWV0ZW9yLW9yZGVy", order.path("attachment").asText());
    }

    private Process launch(String role, Path log, String target, String... registryParameters) throws Exception {
        // 应用依赖图独立声明，测试入口使用标准测试编译输出。
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        command.add("-cp");
        command.add(System.getProperty("meteor.test.application.classpath"));
        command.add("net.hasor.peer.application.OrderApplicationMain");
        command.add(this.protocol);
        String discovery = "direct";
        if (role.startsWith("Nacos")) {
            discovery = "nacos";
        } else if (role.startsWith("Zookeeper")) {
            discovery = "zookeeper";
        }
        String applicationRole = "Consumer";
        if (role.endsWith("ProviderMain")) {
            applicationRole = "Provider";
        }
        command.add(applicationRole);
        command.add(discovery);
        command.addAll(List.of(registryParameters));
        ProcessBuilder launcher = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        launcher.environment().put("METEOR_ORDER_BIND_HOST", "0.0.0.0");
        launcher.environment().put("METEOR_ORDER_PORT", "0");
        if (target != null) {
            launcher.environment().put("METEOR_ORDER_TARGET", target);
        }
        String runtime = "java-meteor";
        if (role.endsWith("ProviderMain")) {
            runtime = "java-meteor-peer";
        }
        return ContainerProcess.start(runtime, launcher);
    }

    private String awaitReady(Process process, Path log) throws Exception {
        // 读取可观察的启动结果，保留异常退出日志并限制等待时长。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String prefix = "Order provider is ready: ";
        while (System.nanoTime() < deadline) {
            String output = Files.readString(log, StandardCharsets.UTF_8);
            for (String line : output.split("\\R")) {
                if (line.startsWith(prefix)) {
                    return line.substring(prefix.length()).trim();
                }
            }
            assertTrue(output, process.isAlive());
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("Provider did not become ready: " + Files.readString(log));
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
