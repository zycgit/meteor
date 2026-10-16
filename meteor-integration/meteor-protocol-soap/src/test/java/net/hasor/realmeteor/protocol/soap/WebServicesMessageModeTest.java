/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;

import java.net.URL;
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

    @Test
    public void unannotatedOneWayMethodIsRejectedOnPublication() throws Exception {
        // WSDL 声明 one-way，但 Java 接口没有方法注解或服务级消息配置。
        MetConfiguration configuration = this.incomingConfiguration();
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/wsdl/signal.wsdl"));
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 注册时即暴露方向不一致，避免推迟到首次编码请求。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> context.publisher() //
                    .rsfService(PlainSignalService.class, new SignalServiceImpl()) //
                    .aliasName("soap", "SignalService") //
                    .protocol("soap") //
                    .bindResource(resource) //
                    .register());
            assertEquals("SOAP message mode does not match WSDL operation: signal", failure.getMessage());
        }
    }

    @Test
    public void annotatedRequestResponseMethodIsRejectedOnPublication() throws Exception {
        // 方法标记为消息调用，但 WSDL 仍声明请求与业务响应。
        MetConfiguration configuration = this.incomingConfiguration();
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/wsdl/echo.wsdl"));
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 反方向不一致同样必须在发布时失败。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> context.publisher() //
                    .rsfService(MessageEchoService.class, message -> message) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(resource) //
                    .register());
            assertEquals("SOAP message mode does not match WSDL operation: echo", failure.getMessage());
        }
    }

    @Test
    public void serviceMessageModeRejectsRequestResponseOperationOnPublication() throws Exception {
        // 整个服务被设为消息模式，其 WSDL 却包含普通 request-response operation。
        MetConfiguration configuration = this.incomingConfiguration();
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/wsdl/echo.wsdl"));
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 服务级配置也必须服从相同的 WSDL 模式校验。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(resource) //
                    .asMessage() //
                    .register());
            assertEquals("SOAP message mode does not match WSDL operation: echo", failure.getMessage());
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
