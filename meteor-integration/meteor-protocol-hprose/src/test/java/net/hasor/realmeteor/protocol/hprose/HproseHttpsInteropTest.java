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
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.hprose.Sample;
import net.hasor.example.protocol.hprose.SampleImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HproseHttpsInteropTest {
    @Test(timeout = 30000)
    public void officialNodeCallsMeteorOverMutualTls() throws Exception {
        // HTTPS 监听要求客户端身份，官方 Node.js 客户端加载受信任的客户端证书。
        MetConfiguration incoming = new MetConfiguration().setQueueMinPoolSize(4);
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "http/1.1") //
                .option("tlsClientAuth", "REQUIRE") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");
        try (MetContext server = incoming.buildContext()) {
            // 启动后通过公开发布入口注册业务接口，外部对端使用标准 Hprose 编码。
            server.start();
            server.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 官方客户端在双向 TLS 上发现服务并验证中文及补充字符往返。
            int port = server.bindAddress("hprose").getPort();
            String target = "https://127.0.0.1:" + port + "/rpc";
            Process peer = this.peer("https-client", target, "unicode", this.tlsDirectory());
            try {
                assertTrue("Node peer timed out", peer.waitFor(20, TimeUnit.SECONDS));
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(output, 0, peer.exitValue());
                assertTrue(output, output.contains("OK"));
            } finally {
                // 任何断言失败都不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test(timeout = 30000)
    public void officialNodeHttpsBatchPreservesOrderAndErrorIsolation() throws Exception {
        // HTTPS 监听要求客户端身份，官方 Node.js 客户端加载受信任的客户端证书。
        MetConfiguration incoming = new MetConfiguration().setQueueMinPoolSize(4);
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "http/1.1") //
                .option("tlsClientAuth", "REQUIRE") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");
        try (MetContext server = incoming.buildContext()) {
            // 启动后通过公开发布入口注册业务接口，外部对端使用标准 Hprose 编码。
            server.start();
            server.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 官方客户端在一个 HTTPS 批次中验证顺序及错误隔离，随后再次调用确认恢复。
            int port = server.bindAddress("hprose").getPort();
            String target = "https://127.0.0.1:" + port + "/rpc";
            Process peer = this.peer("https-client", target, "batch", this.tlsDirectory());
            try {
                assertTrue("Node peer timed out", peer.waitFor(20, TimeUnit.SECONDS));
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(output, 0, peer.exitValue());
                assertTrue(output, output.contains("OK"));
            } finally {
                // 任何断言失败都不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test(timeout = 30000)
    public void officialNodeHttpsByRefReturnsMutatedArguments() throws Exception {
        // HTTPS 监听要求客户端身份，官方 Node.js 客户端加载受信任的客户端证书。
        MetConfiguration incoming = new MetConfiguration().setQueueMinPoolSize(4);
        incoming.connector("http") //
                .bind("127.0.0.1", 0) //
                .option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsKeyStore", "classpath:/tls/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsAlpn", "http/1.1") //
                .option("tlsClientAuth", "REQUIRE") //
                .protocol("hprose").addressScheme("https").contextPath("/rpc");
        try (MetContext server = incoming.buildContext()) {
            // 启动后通过公开发布入口注册业务接口，外部对端使用标准 Hprose 编码。
            server.start();
            server.publisher() //
                    .rsfService(Sample.class, new SampleImpl()) //
                    .aliasName("Hprose", "sample") //
                    .register();

            // 官方客户端在 HTTPS 上读取 byref 参数，验证服务端修改后的参数回传。
            int port = server.bindAddress("hprose").getPort();
            String target = "https://127.0.0.1:" + port + "/rpc";
            Process peer = this.peer("https-client", target, "byref", this.tlsDirectory());
            try {
                assertTrue("Node peer timed out", peer.waitFor(20, TimeUnit.SECONDS));
                byte[] outputBytes = peer.getInputStream().readAllBytes();
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                assertEquals(output, 0, peer.exitValue());
                assertTrue(output, output.contains("OK"));
            } finally {
                // 任何断言失败都不能遗留外部客户端进程。
                peer.destroyForcibly();
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsOfficialNodeOverMutualTls() throws Exception {
        // 官方 Node.js 服务端使用独立身份并要求客户端证书，启动后报告随机端口。
        Process peer = this.peer("https-server", this.tlsDirectory());
        InputStreamReader peerOutput = new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8);
        try (BufferedReader output = new BufferedReader(peerOutput)) {
            int port = Integer.parseInt(output.readLine());
            MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
            outgoing.connector("http") //
                    .option("tlsEnabled", true) //
                    .option("httpScheme", "https") //
                    .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                    .option("tlsTrustStorePassword", "meteor-test") //
                    .option("tlsKeyStore", "classpath:/tls/acceptance/client.p12") //
                    .option("tlsKeyStorePassword", "meteor-test") //
                    .option("tlsAlpn", "http/1.1") //
                    .protocol("hprose").addressScheme("https").contextPath("/rpc");

            try (MetContext client = outgoing.buildContext()) {
                // Meteor 启动后订阅普通 Java 接口，正式 TLS 配置保留服务器主机名校验。
                client.start();
                MetBindInfo<Sample> service = client.publisher() //
                        .rsfService(Sample.class) //
                        .aliasName("Hprose", "sample") //
                        .register();
                InterAddress address = new InterAddress("https", "127.0.0.1", port, "default");
                Sample remote = client.getRsfClient(address).getRemote(service);

                // 由公开代理调用独立的官方实现，验证 HTTPS 下双向协议互通。
                assertEquals("双向认证🙂", remote.echo("双向认证🙂"));
            }
        } finally {
            // 关闭输入通知对端结束，失败或退出超时也回收进程。
            peer.getOutputStream().close();
            if (!peer.waitFor(3, TimeUnit.SECONDS)) {
                peer.destroyForcibly();
            }
        }
    }

    private String tlsDirectory() throws Exception {
        return Path.of(HproseHttpsInteropTest.class.getResource("/tls/acceptance/server.p12").toURI()) //
                .getParent().toString();
    }

    private Process peer(String... arguments) throws Exception {
        // 复用 Gradle 已安装的官方 Node.js 依赖，子进程只负责独立对端行为。
        Path directory = Path.of(System.getProperty("hprose.peer.dir"));
        ArrayList<String> command = new ArrayList<>(List.of("node", "peer.js"));
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command) //
                .directory(directory.toFile()) //
                .redirectErrorStream(true) //
                .start();
    }
}
