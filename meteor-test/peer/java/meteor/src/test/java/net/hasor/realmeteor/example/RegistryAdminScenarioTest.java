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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.hasor.cobble.StringUtils;
import net.hasor.example.OrderExampleUtils;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetClusterAdmin;
import net.hasor.meteor.registry.admin.MetInstanceInfo;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import net.hasor.meteor.test.nxn.NxnRpc;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.storage.NacosTestServer;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分别运行 Nacos、ZooKeeper 管理入口，查询独立提供者已经上报的目录。 */
@NxnRpc(name = "rsf", version = "1")
public class RegistryAdminScenarioTest {
    @TempDir
    public Path files;

    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nacosAdminMainListsPublishedOrderProvider() throws Exception {
        // 复用 worker 的 Nacos 服务，提供者和管理端共用当前句柄的独立分组。
        Path serverHome = Files.createDirectory(this.files.resolve("nacos"));
        try (NacosTestServer registry = new NacosTestServer(serverHome)) {
            registry.start();
            String address = registry.address();
            String group = registry.group();
            Path providerLog = Files.createFile(this.files.resolve("nacos-provider.log"));
            Process provider = this.launch("rsf.NacosRegistryProviderMain", providerLog, address, group);
            try {
                this.awaitReady(provider, providerLog);

                // 提供者启动后异步上报，测试通过公开 API 等待目录可见。
                NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
                configuration.setAddress(address);
                configuration.setGroup(group);
                configuration.setManagementToken("demo-token");
                try (RegistryAdmin directory = configuration.buildAdmin()) {
                    MetClusterAdmin cluster = directory.getClusterAdmin();
                    String serviceId = this.awaitOrderService(cluster);
                    List<MetInstanceInfo> instances = cluster.instances("rsf-order-provider", null).getItems();
                    assertEquals(1, instances.size());
                    String instanceId = instances.get(0).getInstanceId();

                    // 管理 Main 使用自己的连接查询目录，输出当前提供者和订单服务。
                    Path adminLog = Files.createFile(this.files.resolve("nacos-admin.log"));
                    Process administration = this.launch("NacosRegistryAdminMain", adminLog, address, group);
                    String output = this.completed(administration, adminLog);
                    assertTrue(output.contains("Registry provider: nacos"), output);
                    assertTrue(output.contains("Instance: " + instanceId), output);
                    assertTrue(output.contains("Service: " + serviceId), output);
                }
            } finally {
                this.stop(provider);
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void zookeeperAdminMainListsPublishedOrderProvider() throws Exception {
        // 复用 worker 的 ZooKeeper 服务，各个进程共用当前场景的独立 chroot。
        try (ZookeeperTestServer registry = ZookeeperTestServer.shared()) {
            String address = registry.getConnectString();
            Path providerLog = Files.createFile(this.files.resolve("zookeeper-provider.log"));
            Process provider = this.launch("rsf.ZookeeperRegistryProviderMain", providerLog, address, null);
            try {
                this.awaitReady(provider, providerLog);

                // 等待服务登记完成，只将公开查询得到的 ID 用于后续输出断言。
                ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
                configuration.setAddress(address);
                configuration.setManagementToken("demo-token");
                try (RegistryAdmin directory = configuration.buildAdmin()) {
                    MetClusterAdmin cluster = directory.getClusterAdmin();
                    String serviceId = this.awaitOrderService(cluster);
                    List<MetInstanceInfo> instances = cluster.instances("rsf-order-provider", null).getItems();
                    assertEquals(1, instances.size());
                    String instanceId = instances.get(0).getInstanceId();

                    // ZooKeeper 管理 Main 独立建立会话，查询实例和服务目录后正常退出。
                    Path adminLog = Files.createFile(this.files.resolve("zookeeper-admin.log"));
                    Process administration = this.launch("ZookeeperRegistryAdminMain", adminLog, address, null);
                    String output = this.completed(administration, adminLog);
                    assertTrue(output.contains("Registry provider: zookeeper"), output);
                    assertTrue(output.contains("Instance: " + instanceId), output);
                    assertTrue(output.contains("Service: " + serviceId), output);
                }
            } finally {
                this.stop(provider);
            }
        }
    }

    private String awaitOrderService(MetClusterAdmin cluster) throws Exception {
        // 等待注册客户端的自动上报，查询结果只来自当前场景的数据范围。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            List<MetServiceInfo> services = cluster.services(OrderExampleUtils.SERVICE_NAME, null).getItems();
            for (MetServiceInfo service : services) {
                if (StringUtils.equals(OrderExampleUtils.SERVICE_NAME, service.getName())) {
                    return service.getServiceId();
                }
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
        throw new AssertionError("Order service did not appear in the registry directory");
    }

    private Process launch(String entrypoint, Path log, String registryAddress, String registryScope) throws Exception {
        // 应用和管理入口由测试工程持有，运行依赖不包含整个测试宿主 SDK 图。
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        command.add("-cp");
        command.add(System.getProperty("meteor.test.application.classpath"));
        String registry = "zookeeper";
        if (entrypoint.contains("Nacos")) {
            registry = "nacos";
        }
        if (entrypoint.endsWith("AdminMain")) {
            command.add("net.hasor.peer.application.RegistryAdministrationMain");
            command.add(registry);
        } else {
            command.add("net.hasor.peer.application.OrderApplicationMain");
            command.add("rsf");
            command.add("Provider");
            command.add(registry);
        }
        command.add(registryAddress);
        if (StringUtils.equals("nacos", registry)) {
            command.add(registryScope);
        }
        ProcessBuilder launcher = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        launcher.environment().put("METEOR_ORDER_BIND_HOST", "0.0.0.0");
        launcher.environment().put("METEOR_ORDER_PORT", "0");
        String runtime = "java-meteor";
        if (!entrypoint.endsWith("AdminMain")) {
            runtime = "java-meteor-peer";
        }
        return ContainerProcess.start(runtime, launcher);
    }

    private void awaitReady(Process process, Path log) throws Exception {
        // 提供者已监听并发布服务后才继续，异常退出时保留完整启动日志。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            String output = Files.readString(log, StandardCharsets.UTF_8);
            for (String line : output.split("\\R")) {
                if (line.startsWith("Order provider is ready: ")) {
                    return;
                }
            }
            assertTrue(process.isAlive(), output);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("Provider did not become ready: " + Files.readString(log));
    }

    private String completed(Process process, Path log) throws Exception {
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Administration did not exit: " + log);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            this.stop(process);
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
