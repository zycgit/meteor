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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.example.protocol.soap.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebServicesMessageModeTest {
    @Test(timeout = 20000)
    public void mixedServiceOrdinaryMethodKeepsRequestResponseMode() throws Exception {
        // Java 接口同时声明普通方法与消息方法，生成契约保留普通方法的业务返回值。
        MetConfiguration incoming = this.incomingConfiguration();
        MetConfiguration outgoing = this.outgoingConfiguration();
        AtomicBoolean observedMessage = new AtomicBoolean(true);
        SignalServiceImpl notifications = new SignalServiceImpl();
        MixedService service = new MixedServiceImpl(notifications::signal);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 从 Bootstrap 启动两个容器，并发布与订阅相同的混合模式接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MixedService.class, service) //
                    .aliasName("soap", "MixedService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MixedService> subscribed = client.publisher() //
                    .rsfService(MixedService.class) //
                    .aliasName("soap", "MixedService") //
                    .protocol("soap") //
                    .bindFilter("observe-message", (request, response, chain) -> {
                        observedMessage.set(request.isMessage());
                        chain.doFilter(request, response);
                    }) //
                    .register();

            // 真实 HTTP 调用普通方法，过滤器与返回值均遵循 request-response 语义。
            MixedService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscribed);
            assertEquals("ordinary result", remote.echo("ordinary result"));
            assertFalse("Ordinary method was treated as a message", observedMessage.get());
            assertFalse("Mixed service must not enable message mode for every method", subscribed.isMessage());
        }
    }

    @Test(timeout = 20000)
    public void mixedServiceAnnotatedMethodAcknowledgesBeforeBusinessCompletion() throws Exception {
        // 消息方法进入业务后保持阻塞，普通方法与它共享同一个服务绑定。
        BlockingSignalServiceImpl notifications = new BlockingSignalServiceImpl();
        MixedService service = new MixedServiceImpl(notifications::signal);
        AtomicBoolean observedMessage = new AtomicBoolean();
        MetConfiguration incoming = this.incomingConfiguration();
        MetConfiguration outgoing = this.outgoingConfiguration();
        ExecutorService invocation = Executors.newSingleThreadExecutor();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端通过公开 API 发布与订阅，方法注解决定消息模式。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MixedService.class, service) //
                    .aliasName("soap", "MixedService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MixedService> subscribed = client.publisher() //
                    .rsfService(MixedService.class) //
                    .aliasName("soap", "MixedService") //
                    .protocol("soap") //
                    .bindFilter("observe-message", (request, response, chain) -> {
                        observedMessage.set(request.isMessage());
                        chain.doFilter(request, response);
                    }) //
                    .register();

            // 消息请求真实经过 HTTP，调用完成时业务尚未结束，过滤器已看到消息模式。
            MixedService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscribed);
            Future<?> call = invocation.submit(() -> remote.signal("mixed notice"));
            try {
                assertTrue("Business method was not entered", notifications.awaitEntered(5, TimeUnit.SECONDS));
                call.get(2, TimeUnit.SECONDS);
                assertTrue("Caller filter did not see message mode", observedMessage.get());
                assertFalse("Method annotation changed the whole service mode", subscribed.isMessage());
                assertFalse("Business method finished before acknowledgement", notifications.isFinished());
            } finally {
                notifications.release();
            }

            // 收到入队确认后，远端业务仍能够继续执行并消费完整参数。
            assertTrue("Business method did not finish", notifications.awaitFinished(5, TimeUnit.SECONDS));
            assertEquals("mixed notice", notifications.getMessage());
        } finally {
            notifications.release();
            invocation.shutdownNow();
        }
    }

    @Test(timeout = 20000)
    public void asMessageBindingCallsUnannotatedOneWayMethod() throws Exception {
        // 旧接口不使用注解，由发布与订阅端显式配置整个服务的消息模式。
        MetConfiguration incoming = this.incomingConfiguration();
        MetConfiguration outgoing = this.outgoingConfiguration();
        SignalServiceImpl service = new SignalServiceImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布和订阅都通过 asMessage 生成 one-way operation。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(PlainSignalService.class, service) //
                    .aliasName("soap", "SignalService") //
                    .protocol("soap") //
                    .asMessage() //
                    .register();
            MetBindInfo<PlainSignalService> subscribed = client.publisher() //
                    .rsfService(PlainSignalService.class) //
                    .aliasName("soap", "SignalService") //
                    .protocol("soap") //
                    .asMessage() //
                    .register();

            // 客户端正常完成 one-way 网络调用，远端业务收到通知。
            PlainSignalService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscribed);
            remote.signal("legacy notice");
            assertTrue("Message binding was lost", subscribed.isMessage());
            assertTrue("Business method was not invoked", service.awaitInvocation(5, TimeUnit.SECONDS));
            assertEquals("legacy notice", service.getMessage());
        }
    }

    @Test(timeout = 20000)
    public void unannotatedOneWayMethodRemainsLocalAndRejectsSoapRequests() throws Exception {
        // WSDL 声明 one-way，但 Java 接口没有方法注解或服务级消息配置。
        MetConfiguration configuration = this.incomingConfiguration();
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/protocol/wsdl/signal.wsdl"));
        SignalServiceImpl notifications = new SignalServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 协议拒绝无法匹配的调用方向，服务本身仍完成注册。
            MetBindInfo<PlainSignalService> service = context.publisher() //
                    .rsfService(PlainSignalService.class, notifications) //
                    .aliasName("soap", "SignalService") //
                    .protocol("soap") //
                    .bindResource("soap", resource) //
                    .register();

            // 公开查询保留绑定，正式客户端不指定远程地址时仍能调用本地提供者。
            MetBindInfo<?> published = context.getServiceInfo(PlainSignalService.class);
            assertNotNull(published);
            assertEquals(service.getBindID(), published.getBindID());
            PlainSignalService local = context.getRsfClient().getRemote(service);
            local.signal("local notice");
            assertEquals("local notice", notifications.getMessage());

            // 对同一路径发送符合绑定契约的 SOAP 报文，协议明确返回未发布而不执行业务。
            String envelope = """
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                        <soap:Body>
                            <types:signal xmlns:types="urn:meteor:test:signal:types">
                                <types:message>remote notice</types:message>
                            </types:signal>
                        </soap:Body>
                    </soap:Envelope>
                    """;
            int port = context.getListenAddress("soap").getPort();
            URI address = URI.create("http://127.0.0.1:" + port + "/services/SignalService");
            HttpRequest request = HttpRequest.newBuilder(address) //
                    .timeout(Duration.ofSeconds(10)) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"urn:meteor:test:signal\"") //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            assertEquals(404, response.statusCode());
            assertEquals("local notice", notifications.getMessage());
        }
    }

    @Test
    public void annotatedRequestResponseMethodRemainsLocallyCallable() throws Exception {
        // 方法标记为消息调用，但 WSDL 仍声明请求与业务响应。
        MetConfiguration configuration = this.incomingConfiguration();
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/protocol/wsdl/echo.wsdl"));
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 消息方法不能携带业务返回值，当前 SOAP 端点不接纳该服务。
            MetBindInfo<MessageEchoService> service = context.publisher() //
                    .rsfService(MessageEchoService.class, message -> message) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource("soap", resource) //
                    .register();

            // 拒绝协议发布不影响公开查询和客户端优先本地调用。
            MetBindInfo<?> published = context.getServiceInfo(MessageEchoService.class);
            assertNotNull(published);
            assertEquals(service.getBindID(), published.getBindID());
            MessageEchoService local = context.getRsfClient().getRemote(service);
            assertEquals("local annotated result", local.echo("local annotated result"));
        }
    }

    @Test
    public void serviceMessageModeKeepsLocalRequestResponseMethodCallable() throws Exception {
        // 整个服务被设为消息模式，其 WSDL 却包含普通 request-response operation。
        MetConfiguration configuration = this.incomingConfiguration();
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/protocol/wsdl/echo.wsdl"));
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 服务级消息配置与业务返回值冲突，SOAP 不生成该服务的发布元信息。
            MetBindInfo<EchoService> service = context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource("soap", resource) //
                    .asMessage() //
                    .register();

            // 服务仍可公开查询，服务级消息配置不妨碍正式客户端调用本地实现。
            MetBindInfo<?> published = context.getServiceInfo(EchoService.class);
            assertNotNull(published);
            assertEquals(service.getBindID(), published.getBindID());
            EchoService local = context.getRsfClient().getRemote(service);
            assertEquals("local service result", local.echo("local service result"));
        }
    }

    private MetConfiguration incomingConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        return configuration;
    }

    private MetConfiguration outgoingConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .protocol("soap") //
                .contextPath("/services");
        return configuration;
    }

    private URL resource(String path) {
        URL resource = this.getClass().getResource(path);
        assertNotNull("Missing test resource " + path, resource);
        return resource;
    }
}
