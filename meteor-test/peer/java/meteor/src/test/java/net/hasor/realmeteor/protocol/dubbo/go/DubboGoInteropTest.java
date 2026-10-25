/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.dubbo.go;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.echo.TextEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import static org.junit.Assert.*;

/** Apache Dubbo-Go 的经典 Dubbo 消费与 Triple 双向基础调用证据。 */
public class DubboGoInteropTest {
    private static final String SERVICE = "meteor.example.GoEcho";

    @Test(timeout = 60000)
    public void dubboGoConsumerCallsMeteorDubboProvider() throws Exception {
        this.verifyGoConsumer("dubbo");
    }

    @Test(timeout = 60000)
    public void dubboGoConsumerCallsMeteorTripleProvider() throws Exception {
        this.verifyGoConsumer("triple");
    }

    @Test(timeout = 60000)
    public void meteorTripleConsumerCallsDubboGoProvider() throws Exception {
        this.verifyMeteorConsumer("triple");
    }

    private void verifyGoConsumer(String protocol) throws Exception {
        // 普通 Java 接口只发布当前协议，服务身份与 Go ServiceInfo 保持一致。
        MetConfiguration configuration = this.configuration(protocol, true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TextEchoService.class, value -> value + "|meteor") //
                    .group("compat").version("1.0").protocol(protocol) //
                    .aliasName(protocol, SERVICE).serialize("Hessian2").register();
            String target = server.getListenAddress(protocol).getHostPort();

            // 官方 Go SDK 在独立 module 编译的进程中执行一次真实 RPC，不启用重试。
            try (DubboGoPeer peer = DubboGoPeer.start(protocol, "client", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
                assertEquals("Dubbo-Go → Meteor 中文🙂|meteor", result.path("message").asText());
            }
        }
    }

    private void verifyMeteorConsumer(String protocol) throws Exception {
        // 独立 Go SDK 提供者负责监听，Meteor 只创建当前协议的出站端点。
        MetConfiguration configuration = this.configuration(protocol, false);
        try (DubboGoPeer peer = DubboGoPeer.start(protocol, "server", null); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<TextEchoService> service = client.publisher().rsfService(TextEchoService.class) //
                    .group("compat").version("1.0").protocol(protocol) //
                    .aliasName(protocol, SERVICE).serialize("Hessian2").register();
            InterAddress address = new InterAddress(protocol, peer.host(), port, "default");
            TextEchoService remote = client.getRsfClient(address).getRemote(service);

            // Go 业务实现产生独有后缀；不能将本地调用或原样回声误记为对端证据。
            String request = "Meteor → Dubbo-Go 中文🙂";
            assertEquals(request + "|go-" + protocol, remote.echo(request));
        }
    }

    private MetConfiguration configuration(String protocol, boolean listening) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(protocol);
        configuration.setDefaultTimeout(10000);
        boolean triple = StringUtils.equals("triple", protocol);
        String transport = triple ? "http" : "tcp";
        ConnectorSettings connector = configuration.connector(transport);
        if (listening) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        if (triple) {
            connector.option("httpVersion", 2).protocol("triple").contextPath("/");
        } else {
            connector.protocol("dubbo");
        }
        return configuration;
    }
}
