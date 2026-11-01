/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.Parameter;
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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 每个协议分别启动测试自有的提供者和消费者 JVM。 */
@ParameterizedClass(autoCloseArguments = false, name = "{0}", quoteTextArguments = false)
@MethodSource("protocols")
@NxnRpc(name = "rsf", version = "1", cases = "rsf")
@NxnRpc(name = "dubbo", cases = "dubbo")
@NxnRpc(name = "thrift", cases = "thrift")
@NxnRpc(name = "hprose", cases = "hprose")
@NxnRpc(name = "soap", version = "1.1", cases = "soap")
@NxnRpc(name = "jsonrpc", version = "2.0", cases = "jsonrpc")
@NxnRpc(name = "xmlrpc", cases = "xmlrpc")
@NxnRpc(name = "hessian", version = "2", cases = "hessian")
@NxnRpc(name = "grpc", cases = "grpc")
@NxnRpc(name = "grpc-web", cases = "grpc-web")
@NxnRpc(name = "connect", version = "1", cases = "connect")
@NxnRpc(name = "triple", cases = "triple")
@NxnRpc(name = "oncrpc", version = "2", cases = "oncrpc")
public class LauncherScenarioTest {
    @Parameter
    public String protocol;

    @TempDir
    public Path files;
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

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void independentDirectProcessesUseOneProtocol() throws Exception {
        // 提供者绑定独立端口，消费者与它在不同的常驻 Java 容器中运行。
        Path providerLog = Files.createFile(this.files.resolve("provider.log"));
        Process provider = this.launch("ProviderMain", providerLog, null);
        try {
            String target = this.awaitReady(provider, providerLog);

            // 消费者运行真实 Main，业务写入和查询通过网络回到当前提供者。
            Path consumerLog = Files.createFile(this.files.resolve("consumer.log"));
            Process consumer = this.launch("ConsumerMain", consumerLog, target);
            String output = this.completed(consumer, consumerLog);
            this.assertOrder(output);
        } finally {
            this.stop(provider);
        }
    }

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void independentNacosDiscoveryProcessesUseOneProtocol() throws Exception {
        // Nacos 服务由 worker 复用，当前句柄分配独立数据分组。
        Path serverHome = Files.createDirectory(this.files.resolve("nacos"));
        try (NacosTestServer registry = new NacosTestServer(serverHome)) {
            registry.start();
            String address = registry.address();
            String group = registry.group();

            // 提供者运行当前协议的公开 Main，登记它实际监听的业务端点。
            Path providerLog = Files.createFile(this.files.resolve("nacos-provider.log"));
            Process provider = this.launch("NacosRegistryProviderMain", providerLog, null, address, group);
            try {
                this.awaitReady(provider, providerLog);

                // 消费者原样运行，通过注册中心自动发现提供者并保存、查询订单。
                Path consumerLog = Files.createFile(this.files.resolve("nacos-consumer.log"));
                Process consumer = this.launch("NacosRegistryConsumerMain", consumerLog, null, address, group);
                String output = this.completed(consumer, consumerLog);
                this.assertOrder(output);
                assertTrue(output.contains("Discovered addresses:"), output);
            } finally {
                this.stop(provider);
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void independentZookeeperDiscoveryProcessesUseOneProtocol() throws Exception {
        // ZooKeeper 服务由 worker 复用，当前场景的应用共用一个独立 chroot。
        try (ZookeeperTestServer registry = ZookeeperTestServer.shared()) {
            String address = registry.getConnectString();
            // 提供者使用独立的 ZooKeeper 入口，发布当前协议的业务端点。
            Path providerLog = Files.createFile(this.files.resolve("zookeeper-provider.log"));
            Process provider = this.launch("ZookeeperRegistryProviderMain", providerLog, null, address);
            try {
                this.awaitReady(provider, providerLog);

                // 消费者使用独立入口自动发现服务，业务写入和查询通过真实网络完成。
                Path consumerLog = Files.createFile(this.files.resolve("zookeeper-consumer.log"));
                Process consumer = this.launch("ZookeeperRegistryConsumerMain", consumerLog, null, address);
                String output = this.completed(consumer, consumerLog);
                this.assertOrder(output);
                assertTrue(output.contains("Discovered addresses:"), output);
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
            assertTrue(process.isAlive(), output);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("Provider did not become ready: " + Files.readString(log));
    }

    private String completed(Process consumer, Path log) throws Exception {
        try {
            assertTrue(consumer.waitFor(30, TimeUnit.SECONDS), "Consumer did not exit: " + log);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertEquals(0, consumer.exitValue(), output);
            return output;
        } finally {
            this.stop(consumer);
        }
    }

    private void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Process did not exit");
        }
    }
}
