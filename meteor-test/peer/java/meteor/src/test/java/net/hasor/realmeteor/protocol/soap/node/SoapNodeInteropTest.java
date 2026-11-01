/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.soap.node;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.net.URL;
import java.nio.file.Path;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.node.NodeRpcPeer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** SOAP 1.1 document/literal 的最小双向互通，不推断类型或传输的完整支持。 */
@NxnRpc(name = "soap", version = "1.1")
public class SoapNodeInteropTest {

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nodeSoapConsumerCallsMeteorProvider() throws Exception {
        // Meteor 经 bootstrap 启动并发布同一份测试 Echo 契约。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("soap").contextPath("/services");
        URL contract = this.getClass().getResource("/protocol/wsdl/echo.wsdl");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap") //
                    .bindResource("soap", new SoapWsdlResource(contract)).register();

            // node-soap 读取线上 WSDL，自己编码调用并断言 Unicode 与 XML 特殊字符。
            int port = provider.getListenAddress("soap").getPort();
            String wsdl = "http://" + ContainerProcess.runnerHost() + ":" + port + "/services/EchoService?wsdl";
            String message = "node-soap → Meteor 中文 <&>";
            try (NodeRpcPeer consumer = NodeRpcPeer.start("soap", "consumer", wsdl, message)) {
                consumer.requireSuccess();
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerCallsNodeSoapProvider() throws Exception {
        // node-soap 使用自己的监听、WSDL 解析及 SOAP 编解码，不代理 Meteor 服务。
        URL contract = this.getClass().getResource("/protocol/wsdl/echo.wsdl");
        String source = Path.of(contract.toURI()).toString();
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").protocol("soap").contextPath("/services");
        try (NodeRpcPeer provider = NodeRpcPeer.start("soap", "provider", source); //
             MetContext consumer = configuration.buildContext()) {
            int port = provider.awaitPort();
            consumer.start();
            MetBindInfo<EchoService> service = consumer.publisher().rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService").protocol("soap") //
                    .bindResource("soap", new SoapWsdlResource(contract)).register();

            // 普通 Meteor 代理经网络调用独立 Node 提供者并验证真实业务响应。
            InterAddress address = new InterAddress("soap", provider.host(), port, "default");
            EchoService remote = consumer.getRsfClient(address).getRemote(service);
            String message = "Meteor → node-soap 中文 <&>";
            assertEquals(message, remote.echo(message));
        }
    }
}
