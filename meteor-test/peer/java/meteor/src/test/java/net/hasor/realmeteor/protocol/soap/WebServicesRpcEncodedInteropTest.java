/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

@NxnRpc(name = "soap", version = "1.1")
public class WebServicesRpcEncodedInteropTest {
    private static final String NAMESPACE = "urn:hasor:meteor:soap:RpcEchoService";

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void axisClientInvokesMeteorRpcEncodedSoap11() throws Exception {
        // Meteor 发布普通 Java 接口，并自动生成 RPC/encoded SOAP 1.1 契约。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
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
            int port = context.getListenAddress("soap").getPort();
            String authority = "http://" + ContainerProcess.runnerHost() + ":" + port;
            URI endpoint = URI.create(authority + "/services/RpcEchoService");
            Map<String, Object> invocation = new LinkedHashMap<>();
            invocation.put("namespace", NAMESPACE);
            invocation.put("version", "1.1");
            invocation.put("endpoint", endpoint.toString());
            invocation.put("value", "hello Axis RPC 1.1");

            // Axis 在隔离 JVM 中生成完整 encoded 请求、处理媒体类型并读取响应。
            Map<String, Object> response = SoapPeerProcess.axis(invocation);
            Object result = response.get("value");
            assertEquals("hello Axis RPC 1.1", result);
        }
    }

    @Test
    @NxnRpc(name = "soap", version = "1.2")
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void axisClientInvokesMeteorRpcEncodedSoap12() throws Exception {
        // 原厂 Axis 1.4 不发送媒体类型 action，Meteor 明确发布允许省略 action 的 SOAP 1.2 契约。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind(ContainerProcess.runnerHost(), 0) //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", "1.2") //
                .option("soapActionRequired", false) //
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
            int port = context.getListenAddress("soap").getPort();
            String authority = "http://" + ContainerProcess.runnerHost() + ":" + port;
            URI endpoint = URI.create(authority + "/services/RpcEchoService");
            Map<String, Object> invocation = new LinkedHashMap<>();
            invocation.put("namespace", NAMESPACE);
            invocation.put("version", "1.2");
            invocation.put("endpoint", endpoint.toString());
            invocation.put("value", "hello Axis RPC 1.2");

            // Axis 在隔离 JVM 中生成完整 encoded 请求、处理媒体类型并读取响应。
            Map<String, Object> response = SoapPeerProcess.axis(invocation);
            Object result = response.get("value");
            assertEquals("hello Axis RPC 1.2", result);
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorClientInvokesAxisRpcEncodedSoap11Engine() throws Exception {
        // 使用真实 AxisServer 引擎处理 SOAP；测试显式持有外部 HTTP 监听与引擎生命周期。
        try (SoapPeerProcess server = SoapPeerProcess.axisServer()) {
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
                int port = server.getPort();
                InterAddress address = new InterAddress("soap", server.host(), port, "default");
                EchoService remote = context.getRsfClient(address).getRemote(subscribed);
                assertEquals("Axis: hello Meteor RPC 1.1", remote.echo("hello Meteor RPC 1.1"));
            }
        }
    }

    @Test
    @NxnRpc(name = "soap", version = "1.2")
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorClientInvokesAxisRpcEncodedSoap12Engine() throws Exception {
        // 使用真实 AxisServer 引擎处理 SOAP；测试显式持有外部 HTTP 监听与引擎生命周期。
        try (SoapPeerProcess server = SoapPeerProcess.axisServer()) {
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
                int port = server.getPort();
                InterAddress address = new InterAddress("soap", server.host(), port, "default");
                EchoService remote = context.getRsfClient(address).getRemote(subscribed);
                assertEquals("Axis: hello Meteor RPC 1.2", remote.echo("hello Meteor RPC 1.2"));
            }
        }
    }
}
