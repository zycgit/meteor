/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.hprose.Person;
import net.hasor.example.protocol.hprose.Sample;
import net.hasor.example.protocol.hprose.SampleImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.test.nxn.NxnRpc;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

@NxnRpc(name = "hprose")
public class HproseInteropTest {

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialNodeClientUnicode() throws Exception {
        // 在 /rpc 挂载 Hprose，供独立 Node.js 客户端调用。
        MetConfiguration configuration = new MetConfiguration().setQueueMinPoolSize(4);
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose") //
                .contextPath("/rpc");
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布业务接口，Node.js 通过协议发现公开方法。
            context.start();
            context.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 由官方客户端验证中文和补充字符的往返，断言位于 peer.js。
            int port = context.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Process peer = this.peer("client", target, "unicode");
            try {
                // 子进程完成本场景断言后，以退出码和 OK 标记报告结果。
                assertTrue(peer.waitFor(20, TimeUnit.SECONDS), "Node peer timed out");
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(0, peer.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                // 超时或断言失败也不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialNodeClientInteger() throws Exception {
        // 在 /rpc 挂载 Hprose，供独立 Node.js 客户端调用。
        MetConfiguration configuration = new MetConfiguration().setQueueMinPoolSize(4);
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose") //
                .contextPath("/rpc");
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布业务接口，Node.js 通过协议发现公开方法。
            context.start();
            context.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 由官方客户端验证整型参数与结果，断言位于 peer.js。
            int port = context.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Process peer = this.peer("client", target, "integer");
            try {
                // 子进程完成本场景断言后，以退出码和 OK 标记报告结果。
                assertTrue(peer.waitFor(20, TimeUnit.SECONDS), "Node peer timed out");
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(0, peer.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                // 超时或断言失败也不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialNodeClientNull() throws Exception {
        // 在 /rpc 挂载 Hprose，供独立 Node.js 客户端调用。
        MetConfiguration configuration = new MetConfiguration().setQueueMinPoolSize(4);
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose") //
                .contextPath("/rpc");
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布业务接口，Node.js 通过协议发现公开方法。
            context.start();
            context.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 由官方客户端验证 null 的往返，断言位于 peer.js。
            int port = context.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Process peer = this.peer("client", target, "null");
            try {
                // 子进程完成本场景断言后，以退出码和 OK 标记报告结果。
                assertTrue(peer.waitFor(20, TimeUnit.SECONDS), "Node peer timed out");
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(0, peer.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                // 超时或断言失败也不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialNodeClientBytes() throws Exception {
        // 在 /rpc 挂载 Hprose，供独立 Node.js 客户端调用。
        MetConfiguration configuration = new MetConfiguration().setQueueMinPoolSize(4);
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose") //
                .contextPath("/rpc");
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布业务接口，Node.js 通过协议发现公开方法。
            context.start();
            context.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 由官方客户端验证二进制字节的往返，断言位于 peer.js。
            int port = context.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Process peer = this.peer("client", target, "bytes");
            try {
                // 子进程完成本场景断言后，以退出码和 OK 标记报告结果。
                assertTrue(peer.waitFor(20, TimeUnit.SECONDS), "Node peer timed out");
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(0, peer.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                // 超时或断言失败也不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialNodeClientDate() throws Exception {
        // 在 /rpc 挂载 Hprose，供独立 Node.js 客户端调用。
        MetConfiguration configuration = new MetConfiguration().setQueueMinPoolSize(4);
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose") //
                .contextPath("/rpc");
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布业务接口，Node.js 通过协议发现公开方法。
            context.start();
            context.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 由官方客户端验证日期精度，断言位于 peer.js。
            int port = context.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Process peer = this.peer("client", target, "date");
            try {
                // 子进程完成本场景断言后，以退出码和 OK 标记报告结果。
                assertTrue(peer.waitFor(20, TimeUnit.SECONDS), "Node peer timed out");
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(0, peer.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                // 超时或断言失败也不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialNodeClientMap() throws Exception {
        // 在 /rpc 挂载 Hprose，供独立 Node.js 客户端调用。
        MetConfiguration configuration = new MetConfiguration().setQueueMinPoolSize(4);
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose") //
                .contextPath("/rpc");
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布业务接口，Node.js 通过协议发现公开方法。
            context.start();
            context.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 由官方客户端验证 Map 内容，断言位于 peer.js。
            int port = context.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Process peer = this.peer("client", target, "map");
            try {
                // 子进程完成本场景断言后，以退出码和 OK 标记报告结果。
                assertTrue(peer.waitFor(20, TimeUnit.SECONDS), "Node peer timed out");
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(0, peer.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                // 超时或断言失败也不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialNodeClientBean() throws Exception {
        // 在 /rpc 挂载 Hprose，供独立 Node.js 客户端调用。
        MetConfiguration configuration = new MetConfiguration().setQueueMinPoolSize(4);
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose") //
                .contextPath("/rpc");
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布业务接口，Node.js 通过协议发现公开方法。
            context.start();
            context.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 由官方客户端验证 JavaBean 的字段转换，断言位于 peer.js。
            int port = context.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Process peer = this.peer("client", target, "bean");
            try {
                // 子进程完成本场景断言后，以退出码和 OK 标记报告结果。
                assertTrue(peer.waitFor(20, TimeUnit.SECONDS), "Node peer timed out");
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(0, peer.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                // 超时或断言失败也不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialNodeClientVoid() throws Exception {
        // 在 /rpc 挂载 Hprose，供独立 Node.js 客户端调用。
        MetConfiguration configuration = new MetConfiguration().setQueueMinPoolSize(4);
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose") //
                .contextPath("/rpc");
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布业务接口，Node.js 通过协议发现公开方法。
            context.start();
            context.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 由官方客户端验证无返回值方法，断言位于 peer.js。
            int port = context.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Process peer = this.peer("client", target, "void");
            try {
                // 子进程完成本场景断言后，以退出码和 OK 标记报告结果。
                assertTrue(peer.waitFor(20, TimeUnit.SECONDS), "Node peer timed out");
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(0, peer.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                // 超时或断言失败也不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialNodeClientByref() throws Exception {
        // 在 /rpc 挂载 Hprose，供独立 Node.js 客户端调用。
        MetConfiguration configuration = new MetConfiguration().setQueueMinPoolSize(4);
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose") //
                .contextPath("/rpc");
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布业务接口，Node.js 通过协议发现公开方法。
            context.start();
            context.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 由官方客户端验证引用参数回写，断言位于 peer.js。
            int port = context.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Process peer = this.peer("client", target, "byref");
            try {
                // 子进程完成本场景断言后，以退出码和 OK 标记报告结果。
                assertTrue(peer.waitFor(20, TimeUnit.SECONDS), "Node peer timed out");
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(0, peer.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                // 超时或断言失败也不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialNodeClientBatch() throws Exception {
        // 在 /rpc 挂载 Hprose，供独立 Node.js 客户端调用。
        MetConfiguration configuration = new MetConfiguration().setQueueMinPoolSize(4);
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose") //
                .contextPath("/rpc");
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布业务接口，Node.js 通过协议发现公开方法。
            context.start();
            context.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 由官方客户端验证批次顺序和错误隔离，断言位于 peer.js。
            int port = context.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Process peer = this.peer("client", target, "batch");
            try {
                // 子进程完成本场景断言后，以退出码和 OK 标记报告结果。
                assertTrue(peer.waitFor(20, TimeUnit.SECONDS), "Node peer timed out");
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(0, peer.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                // 超时或断言失败也不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialNodeClientDiscovery() throws Exception {
        // 在 /rpc 挂载 Hprose，供独立 Node.js 客户端调用。
        MetConfiguration configuration = new MetConfiguration().setQueueMinPoolSize(4);
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose") //
                .contextPath("/rpc");
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布业务接口，Node.js 通过协议发现公开方法。
            context.start();
            context.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 由官方客户端验证函数发现响应，断言位于 peer.js。
            int port = context.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Process peer = this.peer("client", target, "discovery");
            try {
                // 子进程完成本场景断言后，以退出码和 OK 标记报告结果。
                assertTrue(peer.waitFor(20, TimeUnit.SECONDS), "Node peer timed out");
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(0, peer.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                // 超时或断言失败也不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialNodeClientCors() throws Exception {
        // 在 /rpc 挂载 Hprose，供独立 Node.js 客户端调用。
        MetConfiguration configuration = new MetConfiguration().setQueueMinPoolSize(4);
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose") //
                .contextPath("/rpc");
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布业务接口，Node.js 通过协议发现公开方法。
            context.start();
            context.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 由官方客户端验证跨域预检响应，断言位于 peer.js。
            int port = context.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Process peer = this.peer("client", target, "cors");
            try {
                // 子进程完成本场景断言后，以退出码和 OK 标记报告结果。
                assertTrue(peer.waitFor(20, TimeUnit.SECONDS), "Node peer timed out");
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(0, peer.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                // 超时或断言失败也不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialNodeUnicode() throws Exception {
        // 独立 Node.js 服务端输出实际监听端口，Meteor 不自行模拟对端。
        Process peer = this.peer("server");
        InputStreamReader peerOutput = new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader output = new BufferedReader(peerOutput)) {
            int port = Integer.parseInt(output.readLine());

            // 配置与对端一致的挂载路径，再从 bootstrap 创建客户端。
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("http") //
                    .protocol("hprose") //
                    .contextPath("/rpc");
            try (MetContext client = configuration.buildContext()) {
                // 启动后订阅业务接口，以动态端口构造真实远程代理。
                client.start();
                var publication = client.publisher().rsfService(Sample.class);
                MetBindInfo<Sample> info = publication.aliasName("Hprose", "sample").register();
                InterAddress address = new InterAddress("hprose", ContainerProcess.host(peer), port, "default");
                Sample remote = client.getRsfClient(address).getRemote(info);

                // 中文与补充字符经过双端编解码后保持原样。
                assertEquals("中文🙂", remote.echo("中文🙂"));
            }
        } finally {
            // 关闭标准输入通知 Node.js 正常退出，超时再强制结束。
            peer.getOutputStream().close();
            if (!peer.waitFor(3, TimeUnit.SECONDS)) {
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialNodeInteger() throws Exception {
        // 独立 Node.js 服务端输出实际监听端口，Meteor 不自行模拟对端。
        Process peer = this.peer("server");
        InputStreamReader peerOutput = new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader output = new BufferedReader(peerOutput)) {
            int port = Integer.parseInt(output.readLine());

            // 配置与对端一致的挂载路径，再从 bootstrap 创建客户端。
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("http") //
                    .protocol("hprose") //
                    .contextPath("/rpc");
            try (MetContext client = configuration.buildContext()) {
                // 启动后订阅业务接口，以动态端口构造真实远程代理。
                client.start();
                var publication = client.publisher().rsfService(Sample.class);
                MetBindInfo<Sample> info = publication.aliasName("Hprose", "sample").register();
                InterAddress address = new InterAddress("hprose", ContainerProcess.host(peer), port, "default");
                Sample remote = client.getRsfClient(address).getRemote(info);

                // 两个整数参数由 Node.js 相加，再转换回 Java 整型。
                assertEquals(9, remote.add(4, 5));
            }
        } finally {
            // 关闭标准输入通知 Node.js 正常退出，超时再强制结束。
            peer.getOutputStream().close();
            if (!peer.waitFor(3, TimeUnit.SECONDS)) {
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialNodeBytes() throws Exception {
        // 独立 Node.js 服务端输出实际监听端口，Meteor 不自行模拟对端。
        Process peer = this.peer("server");
        InputStreamReader peerOutput = new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader output = new BufferedReader(peerOutput)) {
            int port = Integer.parseInt(output.readLine());

            // 配置与对端一致的挂载路径，再从 bootstrap 创建客户端。
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("http") //
                    .protocol("hprose") //
                    .contextPath("/rpc");
            try (MetContext client = configuration.buildContext()) {
                // 启动后订阅业务接口，以动态端口构造真实远程代理。
                client.start();
                var publication = client.publisher().rsfService(Sample.class);
                MetBindInfo<Sample> info = publication.aliasName("Hprose", "sample").register();
                InterAddress address = new InterAddress("hprose", ContainerProcess.host(peer), port, "default");
                Sample remote = client.getRsfClient(address).getRemote(info);

                // 验证 Java 有符号 byte 与 Node.js 字节缓冲区的往返。
                byte[] expected = { 0, 127, -1 };
                byte[] result = remote.bytes(new byte[] { 0, 127, -1 });
                assertArrayEquals(expected, result);
            }
        } finally {
            // 关闭标准输入通知 Node.js 正常退出，超时再强制结束。
            peer.getOutputStream().close();
            if (!peer.waitFor(3, TimeUnit.SECONDS)) {
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialNodeDate() throws Exception {
        // 独立 Node.js 服务端输出实际监听端口，Meteor 不自行模拟对端。
        Process peer = this.peer("server");
        InputStreamReader peerOutput = new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader output = new BufferedReader(peerOutput)) {
            int port = Integer.parseInt(output.readLine());

            // 配置与对端一致的挂载路径，再从 bootstrap 创建客户端。
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("http") //
                    .protocol("hprose") //
                    .contextPath("/rpc");
            try (MetContext client = configuration.buildContext()) {
                // 启动后订阅业务接口，以动态端口构造真实远程代理。
                client.start();
                var publication = client.publisher().rsfService(Sample.class);
                MetBindInfo<Sample> info = publication.aliasName("Hprose", "sample").register();
                InterAddress address = new InterAddress("hprose", ContainerProcess.host(peer), port, "default");
                Sample remote = client.getRsfClient(address).getRemote(info);

                // 固定毫秒时间戳，验证日期转换没有损失时间值。
                Date date = new Date(1709210096000L);
                assertEquals(date, remote.date(date));
            }
        } finally {
            // 关闭标准输入通知 Node.js 正常退出，超时再强制结束。
            peer.getOutputStream().close();
            if (!peer.waitFor(3, TimeUnit.SECONDS)) {
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialNodeMap() throws Exception {
        // 独立 Node.js 服务端输出实际监听端口，Meteor 不自行模拟对端。
        Process peer = this.peer("server");
        InputStreamReader peerOutput = new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader output = new BufferedReader(peerOutput)) {
            int port = Integer.parseInt(output.readLine());

            // 配置与对端一致的挂载路径，再从 bootstrap 创建客户端。
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("http") //
                    .protocol("hprose") //
                    .contextPath("/rpc");
            try (MetContext client = configuration.buildContext()) {
                // 启动后订阅业务接口，以动态端口构造真实远程代理。
                client.start();
                var publication = client.publisher().rsfService(Sample.class);
                MetBindInfo<Sample> info = publication.aliasName("Hprose", "sample").register();
                InterAddress address = new InterAddress("hprose", ContainerProcess.host(peer), port, "default");
                Sample remote = client.getRsfClient(address).getRemote(info);

                // Map 键值经过异构客户端后保持一致。
                Map<String, Object> expected = Map.of("key", "值");
                Map<String, Object> result = remote.map(Map.of("key", "值"));
                assertEquals(expected, result);
            }
        } finally {
            // 关闭标准输入通知 Node.js 正常退出，超时再强制结束。
            peer.getOutputStream().close();
            if (!peer.waitFor(3, TimeUnit.SECONDS)) {
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialNodeBean() throws Exception {
        // 独立 Node.js 服务端输出实际监听端口，Meteor 不自行模拟对端。
        Process peer = this.peer("server");
        InputStreamReader peerOutput = new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader output = new BufferedReader(peerOutput)) {
            int port = Integer.parseInt(output.readLine());

            // 配置与对端一致的挂载路径，再从 bootstrap 创建客户端。
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("http") //
                    .protocol("hprose") //
                    .contextPath("/rpc");
            try (MetContext client = configuration.buildContext()) {
                // 启动后订阅业务接口，以动态端口构造真实远程代理。
                client.start();
                var publication = client.publisher().rsfService(Sample.class);
                MetBindInfo<Sample> info = publication.aliasName("Hprose", "sample").register();
                InterAddress address = new InterAddress("hprose", ContainerProcess.host(peer), port, "default");
                Sample remote = client.getRsfClient(address).getRemote(info);

                // 构造带中文字段的业务对象，远端只将年龄增加一岁。
                Person person = new Person();
                person.setName("张三");
                person.setAge(5);
                Person result = remote.person(person);
                assertEquals(6, result.getAge());
                assertEquals("张三", result.getName());
            }
        } finally {
            // 关闭标准输入通知 Node.js 正常退出，超时再强制结束。
            peer.getOutputStream().close();
            if (!peer.waitFor(3, TimeUnit.SECONDS)) {
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialNodeGenericList() throws Exception {
        // 独立 Node.js 服务端输出实际监听端口，Meteor 不自行模拟对端。
        Process peer = this.peer("server");
        InputStreamReader peerOutput = new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader output = new BufferedReader(peerOutput)) {
            int port = Integer.parseInt(output.readLine());

            // 配置与对端一致的挂载路径，再从 bootstrap 创建客户端。
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("http") //
                    .protocol("hprose") //
                    .contextPath("/rpc");
            try (MetContext client = configuration.buildContext()) {
                // 启动后订阅业务接口，以动态端口构造真实远程代理。
                client.start();
                var publication = client.publisher().rsfService(Sample.class);
                MetBindInfo<Sample> info = publication.aliasName("Hprose", "sample").register();
                InterAddress address = new InterAddress("hprose", ContainerProcess.host(peer), port, "default");
                Sample remote = client.getRsfClient(address).getRemote(info);

                // 构造带中文字段的业务对象，远端只将年龄增加一岁。
                Person person = new Person();
                person.setName("张三");
                person.setAge(5);
                List<Person> people = remote.people(List.of(person));
                Person result = people.get(0);
                assertEquals("张三", result.getName());
                assertEquals(6, result.getAge());
            }
        } finally {
            // 关闭标准输入通知 Node.js 正常退出，超时再强制结束。
            peer.getOutputStream().close();
            if (!peer.waitFor(3, TimeUnit.SECONDS)) {
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialNodeVoid() throws Exception {
        // 独立 Node.js 服务端输出实际监听端口，Meteor 不自行模拟对端。
        Process peer = this.peer("server");
        InputStreamReader peerOutput = new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader output = new BufferedReader(peerOutput)) {
            int port = Integer.parseInt(output.readLine());

            // 配置与对端一致的挂载路径，再从 bootstrap 创建客户端。
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("http") //
                    .protocol("hprose") //
                    .contextPath("/rpc");
            try (MetContext client = configuration.buildContext()) {
                // 启动后订阅业务接口，以动态端口构造真实远程代理。
                client.start();
                var publication = client.publisher().rsfService(Sample.class);
                MetBindInfo<Sample> info = publication.aliasName("Hprose", "sample").register();
                InterAddress address = new InterAddress("hprose", ContainerProcess.host(peer), port, "default");
                Sample remote = client.getRsfClient(address).getRemote(info);

                // 无返回值调用正常完成即表示 Node.js 的 null 响应被正确接收。
                remote.accept("void");
            }
        } finally {
            // 关闭标准输入通知 Node.js 正常退出，超时再强制结束。
            peer.getOutputStream().close();
            if (!peer.waitFor(3, TimeUnit.SECONDS)) {
                peer.destroyForcibly();
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialNodeErrorRecovery() throws Exception {
        // 独立 Node.js 服务端输出实际监听端口，Meteor 不自行模拟对端。
        Process peer = this.peer("server");
        InputStreamReader peerOutput = new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader output = new BufferedReader(peerOutput)) {
            int port = Integer.parseInt(output.readLine());

            // 配置与对端一致的挂载路径，再从 bootstrap 创建客户端。
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("http") //
                    .protocol("hprose") //
                    .contextPath("/rpc");
            try (MetContext client = configuration.buildContext()) {
                // 启动后订阅业务接口，以动态端口构造真实远程代理。
                client.start();
                var publication = client.publisher().rsfService(Sample.class);
                MetBindInfo<Sample> info = publication.aliasName("Hprose", "sample").register();
                InterAddress address = new InterAddress("hprose", ContainerProcess.host(peer), port, "default");
                Sample remote = client.getRsfClient(address).getRemote(info);

                // 业务失败只终止本次调用，同一代理后续调用仍然成功。
                assertThrows(Exception.class, remote::fail);
                assertEquals("after error", remote.echo("after error"));
            }
        } finally {
            // 关闭标准输入通知 Node.js 正常退出，超时再强制结束。
            peer.getOutputStream().close();
            if (!peer.waitFor(3, TimeUnit.SECONDS)) {
                peer.destroyForcibly();
            }
        }
    }

    private Process peer(String... arguments) throws Exception {
        // 镜像持有锁定版本的官方依赖，当前测试只传入场景参数。
        Path directory = Path.of(System.getProperty("hprose.peer.dir", "/opt/meteor-peer/node/hprose"));
        ArrayList<String> command = new ArrayList<>(List.of("node", "peer.js"));
        command.addAll(List.of(arguments));

        // 合并错误输出，让测试失败报告保留 Node.js 的完整异常。
        ProcessBuilder builder = new ProcessBuilder(command) //
                .directory(directory.toFile()) //
                .redirectErrorStream(true);
        return ContainerProcess.start("node", builder);
    }
}
