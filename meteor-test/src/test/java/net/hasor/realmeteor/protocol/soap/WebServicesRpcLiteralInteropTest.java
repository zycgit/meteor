/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import javax.xml.namespace.QName;
import jakarta.xml.ws.soap.SOAPBinding;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WebServicesRpcLiteralInteropTest {
    private static final String NAMESPACE = "urn:hasor:meteor:soap:RpcEchoService";

    @Test(timeout = 20000)
    public void metroTypedProxyInvokesMeteorRpcLiteralSoap11() throws Exception {
        // Meteor 以 RPC/literal SOAP 1.1 发布普通 Java 接口，并自动生成契约。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", "1.1") //
                .option("soapStyle", "rpc") //
                .option("soapUse", "literal") //
                .option("soapParameterStyle", "wrapped");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "RpcEchoService") //
                    .protocol("soap") //
                    .register();

            // Metro 从线上 WSDL 创建有 RPC 注解的强类型代理。
            int port = context.getListenAddress("soap").getPort();
            URL wsdl = URI.create("http://127.0.0.1:" + port + "/services/RpcEchoService?wsdl").toURL();
            QName serviceName = new QName(NAMESPACE, "RpcEchoService");
            QName portName = new QName(NAMESPACE, "RpcEchoServicePort");
            Map<String, Object> invocation = SoapPeerProcess.dispatch(wsdl, serviceName, portName, null);
            invocation.put("operation", "typed-rpc");
            invocation.put("value", "hello Metro RPC 1.1");

            // 标准 typed 代理和真实网络调用在独立 Metro JVM 中完成。
            Map<String, Object> result = SoapPeerProcess.metro(invocation);
            assertEquals(SOAPBinding.SOAP11HTTP_BINDING, result.get("bindingId"));
            assertEquals("hello Metro RPC 1.1", result.get("value"));
        }
    }

    @Test(timeout = 20000)
    public void metroTypedProxyInvokesMeteorRpcLiteralSoap12() throws Exception {
        // Meteor 以 RPC/literal SOAP 1.2 发布普通 Java 接口，并自动生成契约。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", "1.2") //
                .option("soapStyle", "rpc") //
                .option("soapUse", "literal") //
                .option("soapParameterStyle", "wrapped");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "RpcEchoService") //
                    .protocol("soap") //
                    .register();

            // Metro 从线上 SOAP 1.2 WSDL 创建同一个 RPC 注解接口的强类型代理。
            int port = context.getListenAddress("soap").getPort();
            URL wsdl = URI.create("http://127.0.0.1:" + port + "/services/RpcEchoService?wsdl").toURL();
            QName serviceName = new QName(NAMESPACE, "RpcEchoService");
            QName portName = new QName(NAMESPACE, "RpcEchoServicePort");
            Map<String, Object> invocation = SoapPeerProcess.dispatch(wsdl, serviceName, portName, null);
            invocation.put("operation", "typed-rpc");
            invocation.put("value", "hello Metro RPC 1.2");

            // 标准 typed 代理和真实网络调用在独立 Metro JVM 中完成。
            Map<String, Object> result = SoapPeerProcess.metro(invocation);
            assertEquals(SOAPBinding.SOAP12HTTP_BINDING, result.get("bindingId"));
            assertEquals("hello Metro RPC 1.2", result.get("value"));
        }
    }

    @Test(timeout = 20000)
    public void meteorClientInvokesMetroRpcLiteralSoap11Endpoint() throws Exception {
        // Metro 从真实 SEI 注解生成 RPC/literal SOAP 1.1 Endpoint。
        try (SoapPeerProcess server = SoapPeerProcess.metroServer("rpc", "1.1", false)) {
            // Meteor 仅启动出站通道，从普通 Java 接口独立生成相同的调用契约。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
            configuration.connector("http") //
                    .protocol("soap") //
                    .contextPath("/services") //
                    .option("soapVersion", "1.1") //
                    .option("soapStyle", "rpc") //
                    .option("soapUse", "literal") //
                    .option("soapParameterStyle", "wrapped");
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> subscribed = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "RpcEchoService") //
                        .protocol("soap") //
                        .register();

                // 公开 Meteor 代理向 Metro 监听地址发起真实 SOAP 1.1 RPC 调用。
                int port = server.getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                EchoService remote = context.getRsfClient(address).getRemote(subscribed);
                assertEquals("hello Meteor RPC 1.1", remote.echo("hello Meteor RPC 1.1"));
            }
        }
    }

    @Test(timeout = 20000)
    public void meteorClientInvokesMetroRpcLiteralSoap12Endpoint() throws Exception {
        // Metro 从同一个真实 SEI 注解生成 RPC/literal SOAP 1.2 Endpoint。
        try (SoapPeerProcess server = SoapPeerProcess.metroServer("rpc", "1.2", false)) {
            // Meteor 仅启动出站通道，从普通 Java 接口独立生成相同的调用契约。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
            configuration.connector("http") //
                    .protocol("soap") //
                    .contextPath("/services") //
                    .option("soapVersion", "1.2") //
                    .option("soapStyle", "rpc") //
                    .option("soapUse", "literal") //
                    .option("soapParameterStyle", "wrapped");
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> subscribed = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "RpcEchoService") //
                        .protocol("soap") //
                        .register();

                // 公开 Meteor 代理向 Metro 监听地址发起真实 SOAP 1.2 RPC 调用。
                int port = server.getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                EchoService remote = context.getRsfClient(address).getRemote(subscribed);
                assertEquals("hello Meteor RPC 1.2", remote.echo("hello Meteor RPC 1.2"));
            }
        }
    }
}
