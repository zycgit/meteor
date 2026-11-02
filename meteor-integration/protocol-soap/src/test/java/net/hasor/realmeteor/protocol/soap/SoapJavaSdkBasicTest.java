/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.net.URI;
import javax.xml.namespace.QName;
import com.sun.xml.ws.developer.JAXWSProperties;
import com.sun.xml.ws.developer.WSBindingProvider;
import jakarta.xml.ws.Service;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.example.protocol.soap.SoapJavaSdkEcho;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.peer.protocol.soap.SoapJavaSdkServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 当前插件与原生 Java SDK 的同 JVM 双向基础网络调用。 */
public class SoapJavaSdkBasicTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialJavaSdkCallsPublishedService() throws Exception {
        // 当前 JUnit 进程通过正式入口启动 Meteor，并发布本插件的普通业务接口。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services") //
                .option("soapVersion", "1.1") //
                .option("soapStyle", "rpc").option("soapUse", "literal") //
                .option("soapParameterStyle", "wrapped");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService").protocol("soap") //
                    .register();

            // 直接使用 SDK 的公开 Java 客户端，仍通过随机监听端口完成真实 HTTP 往返。
            int port = server.getListenAddress("soap").getPort();
            String endpoint = "http://127.0.0.1:" + port + "/services/EchoService?wsdl";
            String namespace = "urn:hasor:meteor:soap:EchoService";
            QName serviceName = new QName(namespace, "EchoService");
            QName portName = new QName(namespace, "EchoServicePort");
            Service sdk = Service.create(URI.create(endpoint).toURL(), serviceName);
            SoapJavaSdkEcho remote = sdk.getPort(portName, SoapJavaSdkEcho.class);
            try (WSBindingProvider binding = (WSBindingProvider) remote) {
                binding.getRequestContext().put(JAXWSProperties.CONNECT_TIMEOUT, 5000);
                binding.getRequestContext().put(JAXWSProperties.REQUEST_TIMEOUT, 10000);
                assertEquals("Java SDK 中文 <&>", remote.echo("Java SDK 中文 <&>"));
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerCallsOfficialJavaSdkServer() throws Exception {
        // SDK 服务与 Meteor 消费者均由测试持有，关闭顺序与创建顺序相反。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("soap").contextPath("/services") //
                .option("soapVersion", "1.1") //
                .option("soapStyle", "rpc").option("soapUse", "literal") //
                .option("soapParameterStyle", "wrapped");
        try (SoapJavaSdkServer server = SoapJavaSdkServer.start(); //
             MetContext client = configuration.buildContext()) {
            client.start();
            MetBindInfo<EchoService> service = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService").protocol("soap") //
                    .register();

            // 前缀只能由 SDK 服务端添加，确保不是同 JVM 的本地调用捷径或普通回显。
            InterAddress address = new InterAddress("soap", "127.0.0.1", server.getPort(), "default");
            EchoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("sdk-server:Java SDK 中文 <&>", remote.echo("Java SDK 中文 <&>"));
        }
    }
}
