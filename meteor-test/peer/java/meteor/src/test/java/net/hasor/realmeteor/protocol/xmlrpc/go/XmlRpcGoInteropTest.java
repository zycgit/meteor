/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.xmlrpc.go;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.golang.GoEchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.golang.GoPeerProcess;
import org.junit.Test;
import static org.junit.Assert.*;

/** kolo/xmlrpc 只提供客户端 API，本类仅记录 Go 消费 Meteor 的基础证据。 */
public class XmlRpcGoInteropTest {
    @Test(timeout = 45000)
    public void goXmlRpcClientCallsMeteorProvider() throws Exception {
        // Meteor 通过公开启动和发布 API 提供 XML-RPC 文本方法。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("xmlrpc");
        configuration.setDefaultTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("xmlrpc").contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(GoEchoService.class, value -> value + "|meteor") //
                    .protocol("xmlrpc").aliasName("xmlrpc", "sample").register();
            String target = "http://" + server.getListenAddress("xmlrpc").getHostPort() + "/rpc";

            // kolo/xmlrpc 原生客户端调用一次 Unicode 回声并核对远端生成的后缀。
            try (GoPeerProcess peer = GoPeerProcess.start("xmlrpc", "client", "plain", "unicode", target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.path("ok").asBoolean());
                assertEquals("Go → Meteor 中文🙂|meteor", result.path("message").asText());
            }
        }
    }
}
