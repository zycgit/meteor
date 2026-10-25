/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.http.go;
import java.net.URI;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.golang.GoEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.golang.GoPeerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Hprose Go 和 Gorilla JSON-RPC2 SDK 的最小双向 HTTP 互通证据。 */
@RunWith(Parameterized.class)
public class GoHttpSdkInteropTest {
    private final String protocol;

    public GoHttpSdkInteropTest(String protocol) {
        this.protocol = protocol;
    }

    @Parameterized.Parameters(name = "compat/go/{0}/direct")
    public static Object[][] protocols() {
        return new Object[][] { { "hprose" }, { "jsonrpc" } };
    }

    @Test(timeout = 45000)
    public void goSdkConsumerCallsMeteorProvider() throws Exception {
        // 当前端点只挂载本协议；Meteor 返回可辨认的后缀，证明请求实际到达提供者。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(GoEchoService.class, value -> value + "|meteor") //
                    .protocol(this.protocol).aliasName(this.aliasType(), "sample").register();
            String target = "http://" + server.getListenAddress(this.protocol).getHostPort() + "/rpc";

            // 独立 Go SDK 负责编解码与 HTTP 调用，校验一次 Unicode 回声。
            try (GoPeerProcess peer = GoPeerProcess.start(this.protocol, "client", "plain", "unicode", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
                assertEquals("Go → Meteor 中文🙂|meteor", result.path("message").asText());
            }
        }
    }

    @Test(timeout = 45000)
    public void meteorConsumerCallsGoSdkProvider() throws Exception {
        // Go SDK 自己开放临时监听；Meteor 仅声明当前协议的出站能力。
        MetConfiguration configuration = this.configuration(false);
        try (GoPeerProcess peer = GoPeerProcess.start(this.protocol, "server", "plain", "unicode", null); //
             MetContext client = configuration.buildContext()) {
            URI endpoint = URI.create(peer.awaitReady());
            client.start();
            MetBindInfo<GoEchoService> subscription = client.publisher().rsfService(GoEchoService.class) //
                    .protocol(this.protocol).aliasName(this.aliasType(), "sample").register();
            InterAddress address = new InterAddress(this.protocol, endpoint.getHost(), endpoint.getPort(), "default");
            GoEchoService remote = client.getRsfClient(address).getRemote(subscription);

            // Go 提供者产生协议专属后缀，避免把本地回声误记为网络互通。
            String request = "Meteor → Go 中文🙂";
            assertEquals(request + "|go-" + this.protocol, remote.echo(request));
        }
    }

    private MetConfiguration configuration(boolean listening) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(this.protocol);
        configuration.setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("http").option("httpVersion", 1);
        if (listening) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        connector.protocol(this.protocol).contextPath("/rpc");
        return configuration;
    }

    private String aliasType() {
        // Hprose 沿用序列化别名键 Hprose，和挂载协议名 hprose 的大小写语义不同。
        if (StringUtils.equals("hprose", this.protocol)) {
            return "Hprose";
        }
        return this.protocol;
    }
}
