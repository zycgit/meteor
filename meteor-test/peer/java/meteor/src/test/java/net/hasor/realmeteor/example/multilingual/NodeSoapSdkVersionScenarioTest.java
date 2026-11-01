/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import net.hasor.cobble.StringUtils;
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

/** node-soap 0.x 和 1.x 通过同一 WSDL 验证双向 document/literal 调用。 */
@NxnRpc(name = "soap", version = "1.1")
@ParameterizedClass(autoCloseArguments = false, name = "sdk/node/soap/{0}@{1}/{2}/{3}", quoteTextArguments = false)
@MethodSource("versions")
public class NodeSoapSdkVersionScenarioTest {
    private final String profile;
    private final String direction;

    public NodeSoapSdkVersionScenarioTest(String sdk, String version, String profile, String direction) {
        this.profile = profile;
        this.direction = direction;
    }
    public static List<Object[]> versions() throws Exception {
        return NodeRpcPeer.compatibilityCases("soap");
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void selectedSdkDirectionPreservesXmlText() throws Exception {
        // 每个参数化实例只验证一个 SDK 版本与调用方向，错误原样向测试框架传播。
        if (StringUtils.equals("SDK-to-Meteor", this.direction)) {
            this.sdkCallsMeteor();
        } else {
            this.meteorCallsSdk();
        }
    }

    private void sdkCallsMeteor() throws Exception {
        // Meteor 按既有 WSDL 发布服务，地址来自实际打开的监听端口。
        URL contract = this.getClass().getResource("/protocol/wsdl/echo.wsdl");
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("soap").contextPath("/services");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap") //
                    .bindResource("soap", new SoapWsdlResource(contract)).register();

            // SDK 自行获取 WSDL、编码 SOAP 请求并校验 XML 特殊字符的往返。
            int port = provider.getListenAddress("soap").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/services/EchoService?wsdl";
            String message = "node-soap → Meteor 中文🙂 <&>";
            try (NodeRpcPeer peer = NodeRpcPeer.startVersion(this.profile, "consumer", target, message)) {
                peer.requireSuccess();
            }
        }
    }

    private void meteorCallsSdk() throws Exception {
        // 当前 SDK 使用原版 WSDL 解析和服务端实现，不在测试器内拼装 SOAP 报文。
        URL contract = this.getClass().getResource("/protocol/wsdl/echo.wsdl");
        String source = Path.of(contract.toURI()).toString();
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(10000);
        configuration.connector("http").protocol("soap").contextPath("/services");
        try (NodeRpcPeer peer = NodeRpcPeer.startVersion(this.profile, "provider", source); //
             MetContext consumer = configuration.buildContext()) {
            int port = peer.awaitPort();
            consumer.start();
            MetBindInfo<EchoService> service = consumer.publisher().rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService").protocol("soap") //
                    .bindResource("soap", new SoapWsdlResource(contract)).register();

            // 普通 Meteor 代理在另一进程的 SDK 服务上验证实际业务响应。
            InterAddress target = new InterAddress("soap", peer.host(), port, "default");
            EchoService remote = consumer.getRsfClient(target).getRemote(service);
            String message = "Meteor → node-soap 中文🙂 <&>";
            assertEquals(message, remote.echo(message));
        }
    }
}
