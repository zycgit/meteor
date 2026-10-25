/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap.go;
import java.net.URL;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.golang.GoPeerProcess;
import org.junit.Test;
import static org.junit.Assert.*;

/** 稳定版 gowsdl 生成的 Go 客户端调用 Meteor 的 SOAP 1.1 document/literal 服务。 */
public class SoapGoInteropTest {
    @Test(timeout = 45000)
    public void gowsdlClientCallsMeteorProvider() throws Exception {
        // Meteor 绑定已有 Echo WSDL；Go 客户端由同一份契约及其导入的 XSD 生成。
        URL contract = this.getClass().getResource("/soap/wsdl/echo.wsdl");
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.setDefaultTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("soap").contextPath("/services");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(EchoService.class, message -> message + "|meteor") //
                    .protocol("soap").aliasName("soap", "EchoService") //
                    .bindResource("soap", new SoapWsdlResource(contract)).register();
            int port = server.getListenAddress("soap").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/services/EchoService";

            // 官方生成客户端执行一次真实网络调用并核对提供者生成的业务后缀。
            try (GoPeerProcess peer = GoPeerProcess.start("soap", "client", "plain", "unicode", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
                assertEquals("Go → Meteor 中文🙂|meteor", result.path("message").asText());
            }
        }
    }
}
