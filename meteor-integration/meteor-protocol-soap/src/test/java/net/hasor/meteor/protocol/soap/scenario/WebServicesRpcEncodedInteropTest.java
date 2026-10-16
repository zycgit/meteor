/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario;
import java.net.InetSocketAddress;
import java.net.URI;
import javax.xml.namespace.QName;
import javax.xml.rpc.ParameterMode;
import com.sun.net.httpserver.HttpServer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.scenario.example.AxisRpcEncodedPeer;
import net.hasor.meteor.protocol.soap.support.EchoService;
import net.hasor.meteor.protocol.soap.scenario.example.EchoServiceImpl;
import org.apache.axis.MessageContext;
import org.apache.axis.client.Call;
import org.apache.axis.client.Service;
import org.apache.axis.encoding.XMLType;
import org.apache.axis.handlers.BasicHandler;
import org.apache.axis.soap.SOAPConstants;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WebServicesRpcEncodedInteropTest {
    private static final String NAMESPACE = "urn:hasor:meteor:soap:RpcEchoService";

    @Test(timeout = 20000)
    public void axisClientInvokesMeteorRpcEncodedSoap11() throws Exception {
        // Meteor 发布普通 Java 接口，并自动生成 RPC/encoded SOAP 1.1 契约。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", "1.1") //
                .option("soapStyle", "rpc") //
                .option("soapUse", "encoded") //
                .option("soapParameterStyle", "wrapped");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "RpcEchoService") //
                    .protocol("soap") //
                    .register();

            // Axis Call 根据真实 RPC/encoded 参数描述生成 SOAP 1.1 请求。
            int port = context.bindAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/RpcEchoService");
            Call call = (Call) new Service().createCall();
            call.setSOAPVersion(SOAPConstants.SOAP11_CONSTANTS);
            call.setTargetEndpointAddress(endpoint.toURL());
            call.setOperationName(new QName(NAMESPACE, "echo"));
            call.setOperationStyle("rpc");
            call.setOperationUse("encoded");
            call.setEncodingStyle(SOAPConstants.SOAP11_CONSTANTS.getEncodingURI());
            call.setUseSOAPAction(true);
            call.setSOAPActionURI(NAMESPACE + "/echo");
            call.addParameter(new QName("", "arg0"), XMLType.XSD_STRING, String.class, ParameterMode.IN);
            call.setReturnType(XMLType.XSD_STRING, String.class);
            call.setReturnQName(new QName("", "return"));
            call.setTimeout(5000);

            // Axis 负责发送真实 HTTP 请求并解码 Meteor 返回的 encoded 结果。
            Object result = call.invoke(new Object[] { "hello Axis RPC 1.1" });
            assertEquals("hello Axis RPC 1.1", result);
        }
    }

    @Test(timeout = 20000)
    public void axisClientInvokesMeteorRpcEncodedSoap12() throws Exception {
        // Meteor 发布普通 Java 接口，并自动生成 RPC/encoded SOAP 1.2 契约。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", "1.2") //
                .option("soapStyle", "rpc") //
                .option("soapUse", "encoded") //
                .option("soapParameterStyle", "wrapped");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "RpcEchoService") //
                    .protocol("soap") //
                    .register();

            // Axis Call 根据真实 RPC/encoded 参数描述生成 SOAP 1.2 请求。
            int port = context.bindAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/RpcEchoService");
            Call call = (Call) new Service().createCall();
            call.setSOAPVersion(SOAPConstants.SOAP12_CONSTANTS);
            call.setTargetEndpointAddress(endpoint.toURL());
            call.setOperationName(new QName(NAMESPACE, "echo"));
            call.setOperationStyle("rpc");
            call.setOperationUse("encoded");
            call.setEncodingStyle(SOAPConstants.SOAP12_CONSTANTS.getEncodingURI());
            call.setUseSOAPAction(true);
            call.setSOAPActionURI(NAMESPACE + "/echo");
            call.addParameter(new QName("", "arg0"), XMLType.XSD_STRING, String.class, ParameterMode.IN);
            call.setReturnType(XMLType.XSD_STRING, String.class);
            call.setReturnQName(new QName("", "return"));
            call.setTimeout(5000);

            // 通过 Axis 的正式请求 handler 为 SOAP 1.2 媒体类型声明 action，正文仍由 Axis 编码。
            call.setClientHandlers(new BasicHandler() {
                @Override
                public void invoke(MessageContext request) {
                    String contentType = "application/soap+xml; charset=utf-8; action=\"" + NAMESPACE + "/echo\"";
                    request.getRequestMessage().getMimeHeaders().setHeader("Content-Type", contentType);
                }
            }, null);

            // Axis 负责发送真实 HTTP 请求，并解码包含标准 RPC result 标记的响应。
            Object result = call.invoke(new Object[] { "hello Axis RPC 1.2" });
            assertEquals("hello Axis RPC 1.2", result);
        }
    }

    @Test(timeout = 20000)
    public void meteorClientInvokesAxisRpcEncodedSoap11Engine() throws Exception {
        // 使用真实 AxisServer 引擎处理 SOAP；测试显式持有外部 HTTP 监听与引擎生命周期。
        try (AxisRpcEncodedPeer peer = new AxisRpcEncodedPeer()) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                server.createContext("/services/RpcEchoService", peer);
                server.start();

                // Meteor 独立生成 RPC/encoded SOAP 1.1 契约，只配置出站通道。
                MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
                configuration.connector("http") //
                        .protocol("soap") //
                        .contextPath("/services") //
                        .option("soapVersion", "1.1") //
                        .option("soapStyle", "rpc") //
                        .option("soapUse", "encoded") //
                        .option("soapParameterStyle", "wrapped");
                try (MetContext context = configuration.buildContext()) {
                    context.start();
                    MetBindInfo<EchoService> subscribed = context.publisher() //
                            .rsfService(EchoService.class) //
                            .aliasName("soap", "RpcEchoService") //
                            .protocol("soap") //
                            .register();

                    // 公开代理经过真实网络，由 Axis 的 Java RPC provider 调用业务实现。
                    int port = server.getAddress().getPort();
                    InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                    EchoService remote = context.getRsfClient(address).getRemote(subscribed);
                    assertEquals("Axis: hello Meteor RPC 1.1", remote.echo("hello Meteor RPC 1.1"));
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test(timeout = 20000)
    public void meteorClientInvokesAxisRpcEncodedSoap12Engine() throws Exception {
        // 使用真实 AxisServer 引擎处理 SOAP；测试显式持有外部 HTTP 监听与引擎生命周期。
        try (AxisRpcEncodedPeer peer = new AxisRpcEncodedPeer()) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                server.createContext("/services/RpcEchoService", peer);
                server.start();

                // Meteor 独立生成 RPC/encoded SOAP 1.2 契约，只配置出站通道。
                MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
                configuration.connector("http") //
                        .protocol("soap") //
                        .contextPath("/services") //
                        .option("soapVersion", "1.2") //
                        .option("soapStyle", "rpc") //
                        .option("soapUse", "encoded") //
                        .option("soapParameterStyle", "wrapped");
                try (MetContext context = configuration.buildContext()) {
                    context.start();
                    MetBindInfo<EchoService> subscribed = context.publisher() //
                            .rsfService(EchoService.class) //
                            .aliasName("soap", "RpcEchoService") //
                            .protocol("soap") //
                            .register();

                    // Axis 生成 SOAP 1.2 encoded 响应和 rpc:result，Meteor 必须正确解码。
                    int port = server.getAddress().getPort();
                    InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                    EchoService remote = context.getRsfClient(address).getRemote(subscribed);
                    assertEquals("Axis: hello Meteor RPC 1.2", remote.echo("hello Meteor RPC 1.2"));
                }
            } finally {
                server.stop(0);
            }
        }
    }
}
