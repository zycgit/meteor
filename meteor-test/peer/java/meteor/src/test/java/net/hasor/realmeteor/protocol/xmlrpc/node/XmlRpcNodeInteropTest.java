/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.xmlrpc.node;
import net.hasor.meteor.test.nxn.NxnRpc;
import net.hasor.example.echo.TextEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.node.NodeRpcPeer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** node-xmlrpc 与 Meteor 的最小字符串调用矩阵，各方向独立验证。 */
@NxnRpc(name = "xmlrpc")
public class XmlRpcNodeInteropTest {

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nodeXmlRpcConsumerCallsMeteorProvider() throws Exception {
        // 从正式 bootstrap 入口启动 XML-RPC 监听，再发布普通 Java 接口。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("xmlrpc").contextPath("/rpc");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TextEchoService.class, text -> text) //
                    .aliasName("xmlrpc", "demo").protocol("xmlrpc").register();

            // node-xmlrpc 自行发送业务请求，并校验 XML 转义和 Unicode 字符不变。
            int port = provider.getListenAddress("xmlrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            String message = "node-xmlrpc → Meteor 中文 <&>";
            try (NodeRpcPeer consumer = NodeRpcPeer.start("xmlrpc", "consumer", target, message)) {
                consumer.requireSuccess();
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerCallsNodeXmlRpcProvider() throws Exception {
        // 独立 node-xmlrpc 服务只实现回声方法，监听端口由本场景申请。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").protocol("xmlrpc").contextPath("/rpc");
        try (NodeRpcPeer provider = NodeRpcPeer.start("xmlrpc", "provider"); //
             MetContext consumer = configuration.buildContext()) {
            int port = provider.awaitPort();
            consumer.start();
            MetBindInfo<TextEchoService> service = consumer.publisher().rsfService(TextEchoService.class) //
                    .aliasName("xmlrpc", "demo").protocol("xmlrpc").register();

            // Meteor 的公开客户端直接调用 SDK 提供者，响应必须来自真实 HTTP 链路。
            InterAddress address = new InterAddress("xmlrpc", provider.host(), port, "default");
            TextEchoService remote = consumer.getRsfClient(address).getRemote(service);
            String message = "Meteor → node-xmlrpc 中文 <&>";
            assertEquals(message, remote.echo(message));
        }
    }
}
