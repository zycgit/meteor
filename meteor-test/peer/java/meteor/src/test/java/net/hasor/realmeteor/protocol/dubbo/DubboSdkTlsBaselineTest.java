/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.dubbo;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import static org.junit.Assert.*;

/** 原厂 SDK 双 JVM 自体调用，确认固定组合的 TLS 能力可用。 */
public class DubboSdkTlsBaselineTest {
    @Test(timeout = 60000)
    public void officialSdkCallsOfficialProviderOverTls() throws Exception {
        // 原厂提供者使用服务端证书，双方都不加载 Meteor 的协议与传输实现。
        try (DubboProcess provider = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0", "--tls=true", //
                "--certificate=" + this.pem("server.pem"), //
                "--private-key=" + this.pem("server-key.pem"))) {
            int port = provider.awaitPort();

            // 同一依赖组合的原厂消费者信任 CA，只发起一次真实业务调用。
            try (DubboProcess consumer = DubboProcess.start("--command=client", //
                    "--host=" + provider.host(), "--port=" + port, "--scenario=echo", //
                    "--group=interop", "--version=1.0.0", "--tls=true", //
                    "--certificate=" + this.pem("client.pem"), //
                    "--private-key=" + this.pem("client-key.pem"), //
                    "--trust-certificate=" + this.pem("ca.pem"))) {
                JsonNode result = consumer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Dubbo 中文 🙂", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void officialSdkCallsOfficialProviderOverMutualTls() throws Exception {
        // 原厂提供者同时信任客户端 CA，要求消费者提交有效客户端身份。
        try (DubboProcess provider = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0", "--tls=true", //
                "--certificate=" + this.pem("server.pem"), //
                "--private-key=" + this.pem("server-key.pem"), //
                "--trust-certificate=" + this.pem("ca.pem"))) {
            int port = provider.awaitPort();

            // 独立消费者通过原厂 SSL 配置提交证书并验证服务端证书链。
            try (DubboProcess consumer = DubboProcess.start("--command=client", //
                    "--host=" + provider.host(), "--port=" + port, "--scenario=echo", //
                    "--group=interop", "--version=1.0.0", "--tls=true", //
                    "--certificate=" + this.pem("client.pem"), //
                    "--private-key=" + this.pem("client-key.pem"), //
                    "--trust-certificate=" + this.pem("ca.pem"))) {
                JsonNode result = consumer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Dubbo 中文 🙂", result.path("value").asText());
            }
        }
    }

    private String pem(String filename) throws Exception {
        return Path.of(this.getClass().getResource("/tls/acceptance/" + filename).toURI()).toString();
    }
}
