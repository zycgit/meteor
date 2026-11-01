/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.container;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.test.nxn.NxnRpc;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 提供者、消费者、注册存储各自拥有网络命名空间；JUnit 只编排，不代发业务调用。 */
@NxnRpc(name = "rsf", version = "1")
public class MeteorContainerNetworkScenarioTest {
    @Test
    @Timeout(value = 180000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void independentMeteorContainersExchangeOrdersThroughDirectRsf() throws Exception {
        // 仅配置直连能力，应用容器分别通过公开 bootstrap 启动自身的提供者与消费者。
        Path home = workspace("direct");
        Properties settings = new Properties();
        settings.setProperty("backend", "direct");
        writeSettings(home, settings);
        try (MeteorOrderContainerProcess provider = MeteorOrderContainerProcess.start("provider", home, null)) {
            Properties ready = provider.awaitReady();
            String target = provider.directAddress(ready);
            try (MeteorOrderContainerProcess consumer = MeteorOrderContainerProcess.start("consumer", home, target)) {
                Properties result = consumer.awaitResult();

                // 两个独立应用容器传递 Unicode、整数和字节数据，消费者没有监听端口。
                assertOrderAndIsolation(ready, result);
                assertEquals(target, result.getProperty("target"));
                assertTrue(new InterAddress(target).getHost().endsWith(".meteor.test"));
            }
        }
    }

    @Test
    @Timeout(value = 240000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void independentMeteorContainersDiscoverOrdersThroughJdbc() throws Exception {
        // JDBC 为第三个独立存储容器，两端只接收数据库设置，不传业务目标地址。
        Path home = workspace("jdbc");
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open("jdbc", home)) {
            writeSettings(home, environment.registryProperties());
            try (MeteorOrderContainerProcess provider = MeteorOrderContainerProcess.start("provider", home, null)) {
                Properties ready = provider.awaitReady();
                try (MeteorOrderContainerProcess consumer = MeteorOrderContainerProcess.start("consumer", home, null)) {
                    Properties result = consumer.awaitResult();

                    // 由应用自身的注册订阅选择实际端点，测试线程不参与服务地址解析。
                    assertOrderAndIsolation(ready, result);
                    assertEquals(ready.getProperty("address"), result.getProperty("target"));
                }
            }
        }
    }

    @Test
    @Timeout(value = 240000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void independentMeteorContainersDiscoverOrdersThroughZookeeper() throws Exception {
        // ZooKeeper 独占当前测试根路径，两个应用使用同一组公开存储配置接入。
        Path home = workspace("zookeeper");
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open("zookeeper", home)) {
            writeSettings(home, environment.registryProperties());
            try (MeteorOrderContainerProcess provider = MeteorOrderContainerProcess.start("provider", home, null)) {
                Properties ready = provider.awaitReady();
                try (MeteorOrderContainerProcess consumer = MeteorOrderContainerProcess.start("consumer", home, null)) {
                    Properties result = consumer.awaitResult();

                    // 消费者只经自动发现调用提供者，业务数据没有经过 runner 或控制文件转发。
                    assertOrderAndIsolation(ready, result);
                    assertEquals(ready.getProperty("address"), result.getProperty("target"));
                }
            }
        }
    }

    @Test
    @Timeout(value = 300000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void independentMeteorContainersDiscoverOrdersThroughNacos() throws Exception {
        // Nacos 独占当前测试分组，发布和订阅由两个应用容器分别维护自己的会话。
        Path home = workspace("nacos");
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open("nacos", home)) {
            writeSettings(home, environment.registryProperties());
            try (MeteorOrderContainerProcess provider = MeteorOrderContainerProcess.start("provider", home, null)) {
                Properties ready = provider.awaitReady();
                try (MeteorOrderContainerProcess consumer = MeteorOrderContainerProcess.start("consumer", home, null)) {
                    Properties result = consumer.awaitResult();

                    // 相同业务契约通过订阅端点跨容器调用，发现和结果均来自应用自身公开 API。
                    assertOrderAndIsolation(ready, result);
                    assertEquals(ready.getProperty("address"), result.getProperty("target"));
                }
            }
        }
    }

    private static Path workspace(String backend) throws Exception {
        // 共享工作卷保存应用控制文件和各自日志，不使用容器私有的 /tmp 目录。
        String directory = System.getProperty("meteor.test.container.workDirectory");
        if (StringUtils.isBlank(directory)) {
            throw new IllegalStateException("meteor.test.container.workDirectory is required");
        }
        Path home = Path.of(directory).resolve(backend + "-" + UUID.randomUUID());
        return Files.createDirectories(home);
    }

    private static void writeSettings(Path home, Properties settings) throws Exception {
        // 示例数据只作为消费者输入；保存后的业务状态始终留在提供者进程内存中。
        settings.setProperty("order.id", "731");
        settings.setProperty("order.description", "容器订单 / café / 🚀");
        byte[] attachment = new byte[] { 0, 1, 63, 127, -128, -1 };
        settings.setProperty("order.attachment", Base64.getEncoder().encodeToString(attachment));
        try (Writer writer = Files.newBufferedWriter(home.resolve("settings.properties"), StandardCharsets.UTF_8)) {
            settings.store(writer, null);
        }
    }

    private static void assertOrderAndIsolation(Properties ready, Properties result) throws Exception {
        assertEquals("731", result.getProperty("saved.id"));
        assertEquals("731", result.getProperty("found.id"));
        assertEquals("容器订单 / café / 🚀", result.getProperty("found.description"));
        byte[] expectedAttachment = new byte[] { 0, 1, 63, 127, -128, -1 };
        byte[] foundAttachment = Base64.getDecoder().decode(result.getProperty("found.attachment"));
        assertArrayEquals(expectedAttachment, foundAttachment);
        assertEquals("false", result.getProperty("listening"));
        assertNotEquals(ready.getProperty("hostname"), result.getProperty("hostname"));
        assertFalse(new InterAddress(result.getProperty("target")).toSocketAddress().getAddress().isLoopbackAddress());
    }
}
