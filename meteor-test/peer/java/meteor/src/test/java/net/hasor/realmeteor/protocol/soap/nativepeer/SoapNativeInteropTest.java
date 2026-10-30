/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.soap.nativepeer;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertEquals;

/** 以相同的 SOAP 1.1 document/literal Echo 契约确认 C/C++ 的最小双向范围。 */
@RunWith(Parameterized.class)
public class SoapNativeInteropTest {
    private final String language;
    private final String executable;

    public SoapNativeInteropTest(String language, String version, String profile, String executable) {
        this.language = language;
        this.executable = executable;
    }

    @Parameterized.Parameters(name = "sdk/{0}/soap/gSOAP@{1}/{2}")
    public static Collection<Object[]> languages() {
        // 同份 WSDL 分别由系统基线与最新工具生成，运行库保持同版。
        Collection<Object[]> languages = new ArrayList<>();
        languages.add(new Object[] { "c", "2.8.124", "debian-baseline", "/opt/meteor-peer/native/soap-c/peer" });
        languages.add(new Object[] { "cpp", "2.8.124", "debian-baseline", "/opt/meteor-peer/native/soap-cpp/peer" });
        String latest = "/opt/meteor-peer/native/versions/soap/2.8.146/";
        languages.add(new Object[] { "c", "2.8.146", "upstream-latest", latest + "c/peer" });
        languages.add(new Object[] { "cpp", "2.8.146", "upstream-latest", latest + "cpp/peer" });
        return languages;
    }

    @Test(timeout = 60000)
    public void sdkToMeteorSoapEcho() throws Exception {
        // 通过公开 bootstrap 启动 Meteor，并使用测试契约发布 Echo 服务。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("soap").contextPath("/services");
        URL contract = this.getClass().getResource("/protocol/wsdl/echo.wsdl");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap") //
                    .bindResource("soap", new SoapWsdlResource(contract)).register();

            // 原生 SDK 调用真实网络端点，校验 Unicode 与 XML 特殊字符的往返。
            int port = provider.getListenAddress("soap").getPort();
            String endpoint = "http://" + ContainerProcess.runnerHost() + ":" + port + "/services/EchoService";
            String message = "gSOAP " + this.language + " → Meteor 中文 <&>";
            String executable = this.executable;
            try (NativeSoapPeer consumer = NativeSoapPeer.startExecutable(executable, "consumer", endpoint, message)) {
                consumer.requireSuccess();
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorToSdkSoapEcho() throws Exception {
        // gSOAP 原生服务独立监听端口，Meteor 仅配置出站协议，不持有本地实现。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").protocol("soap").contextPath("/services");
        URL contract = this.getClass().getResource("/protocol/wsdl/echo.wsdl");
        try (NativeSoapPeer provider = NativeSoapPeer.startExecutable(this.executable, "provider"); //
             MetContext consumer = configuration.buildContext()) {
            int port = provider.awaitPort();
            consumer.start();
            MetBindInfo<EchoService> service = consumer.publisher().rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService").protocol("soap") //
                    .bindResource("soap", new SoapWsdlResource(contract)).register();

            // 公开代理向独立原生服务调用，返回内容由 gSOAP 的 XML 绑定产生。
            InterAddress address = new InterAddress("soap", provider.host(), port, "default");
            EchoService remote = consumer.getRsfClient(address).getRemote(service);
            String message = "Meteor → gSOAP " + this.language + " 中文 <&>";
            assertEquals(message, remote.echo(message));
        }
    }
}
