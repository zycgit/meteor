/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.soap.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetXmlMappingScenarioTest {
    @Test(timeout = 30000)
    public void soap11KeepsSameLocalNameInDifferentNamespaces() throws Exception {
        // 两端先启动，再发布与订阅仅使用 Meteor XML 注解的普通 Java 接口。
        MetConfiguration incoming = this.configuration(true, "1.1");
        MetConfiguration outgoing = this.configuration(false, "1.1");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlOrderService.class, value -> value) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlOrderService> subscription = client.publisher() //
                    .rsfService(MetXmlOrderService.class) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();

            // 经真实 HTTP 调用，让相同映射分别参与请求编码、服务端解码和响应往返。
            MetXmlOrder input = this.order();
            input.setBuyer("Alice");
            input.setDelivery("Bob");
            MetXmlOrderService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscription);
            MetXmlOrder result = remote.echo(input);

            // 通过业务对象验证本场景的 XML 映射语义。
            assertEquals("Alice", result.getBuyer());
            assertEquals("Bob", result.getDelivery());
            assertEquals("order-42", result.getId());
        }
    }

    @Test(timeout = 30000)
    public void soap12KeepsSameLocalNameInDifferentNamespaces() throws Exception {
        // 两端先启动，再发布与订阅仅使用 Meteor XML 注解的普通 Java 接口。
        MetConfiguration incoming = this.configuration(true, "1.2");
        MetConfiguration outgoing = this.configuration(false, "1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlOrderService.class, value -> value) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlOrderService> subscription = client.publisher() //
                    .rsfService(MetXmlOrderService.class) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();

            // 经真实 HTTP 调用，让相同映射分别参与请求编码、服务端解码和响应往返。
            MetXmlOrder input = this.order();
            input.setBuyer("Alice");
            input.setDelivery("Bob");
            MetXmlOrderService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscription);
            MetXmlOrder result = remote.echo(input);

            // 通过业务对象验证本场景的 XML 映射语义。
            assertEquals("Alice", result.getBuyer());
            assertEquals("Bob", result.getDelivery());
            assertEquals("order-42", result.getId());
        }
    }

    @Test(timeout = 30000)
    public void transientPropertyDoesNotCrossTheSoapBoundary() throws Exception {
        // 两端先启动，再发布与订阅仅使用 Meteor XML 注解的普通 Java 接口。
        MetConfiguration incoming = this.configuration(true, "1.1");
        MetConfiguration outgoing = this.configuration(false, "1.1");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlOrderService.class, value -> value) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlOrderService> subscription = client.publisher() //
                    .rsfService(MetXmlOrderService.class) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();

            // 经真实 HTTP 调用，让相同映射分别参与请求编码、服务端解码和响应往返。
            MetXmlOrder input = this.order();
            input.setInternalToken("local-secret");
            MetXmlOrderService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscription);
            MetXmlOrder result = remote.echo(input);

            // 通过业务对象验证本场景的 XML 映射语义。
            assertNull(result.getInternalToken());
            assertNotNull(input.getInternalToken());
        }
    }

    @Test(timeout = 30000)
    public void enumUsesTheDeclaredXmlLexicalValue() throws Exception {
        // 两端先启动，再发布与订阅仅使用 Meteor XML 注解的普通 Java 接口。
        MetConfiguration incoming = this.configuration(true, "1.1");
        MetConfiguration outgoing = this.configuration(false, "1.1");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlOrderService.class, value -> value) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlOrderService> subscription = client.publisher() //
                    .rsfService(MetXmlOrderService.class) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();

            // 经真实 HTTP 调用，让相同映射分别参与请求编码、服务端解码和响应往返。
            MetXmlOrder input = this.order();
            input.setState(MetXmlState.SENT);
            MetXmlOrderService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscription);
            MetXmlOrder result = remote.echo(input);

            // 通过业务对象验证本场景的 XML 映射语义。
            assertEquals(MetXmlState.SENT, result.getState());
        }
    }

    @Test(timeout = 30000)
    public void wrappedNullCollectionRemainsNull() throws Exception {
        // 启动双端容器，普通 DTO 决定集合在 XML 中的包装方式。
        MetConfiguration incoming = this.configuration(true, "1.1");
        MetConfiguration outgoing = this.configuration(false, "1.1");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlCollectionService.class, value -> value) //
                    .aliasName("soap", "MetXmlCollectionService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlCollectionService> subscription = client.publisher() //
                    .rsfService(MetXmlCollectionService.class) //
                    .aliasName("soap", "MetXmlCollectionService") //
                    .protocol("soap") //
                    .register();

            // 准备本场景的集合内容，通过公开客户端完成一次 SOAP 往返。
            MetXmlCollections input = new MetXmlCollections();
            input.setWrapped(null);
            MetXmlCollectionService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);
            MetXmlCollections result = remote.echo(input);

            // 保留集合容器和集合元素各自的空值语义。
            assertNull(result.getWrapped());
        }
    }

    @Test(timeout = 30000)
    public void wrappedEmptyCollectionRemainsEmpty() throws Exception {
        // 启动双端容器，普通 DTO 决定集合在 XML 中的包装方式。
        MetConfiguration incoming = this.configuration(true, "1.1");
        MetConfiguration outgoing = this.configuration(false, "1.1");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlCollectionService.class, value -> value) //
                    .aliasName("soap", "MetXmlCollectionService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlCollectionService> subscription = client.publisher() //
                    .rsfService(MetXmlCollectionService.class) //
                    .aliasName("soap", "MetXmlCollectionService") //
                    .protocol("soap") //
                    .register();

            // 准备本场景的集合内容，通过公开客户端完成一次 SOAP 往返。
            MetXmlCollections input = new MetXmlCollections();
            input.setWrapped(List.of());
            MetXmlCollectionService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);
            MetXmlCollections result = remote.echo(input);

            // 保留集合容器和集合元素各自的空值语义。
            assertNotNull(result.getWrapped());
            assertTrue(result.getWrapped().isEmpty());
        }
    }

    @Test(timeout = 30000)
    public void wrappedSingleNilElementDoesNotBecomeANullContainer() throws Exception {
        // 启动双端容器，普通 DTO 决定集合在 XML 中的包装方式。
        MetConfiguration incoming = this.configuration(true, "1.1");
        MetConfiguration outgoing = this.configuration(false, "1.1");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlCollectionService.class, value -> value) //
                    .aliasName("soap", "MetXmlCollectionService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlCollectionService> subscription = client.publisher() //
                    .rsfService(MetXmlCollectionService.class) //
                    .aliasName("soap", "MetXmlCollectionService") //
                    .protocol("soap") //
                    .register();

            // 准备本场景的集合内容，通过公开客户端完成一次 SOAP 往返。
            MetXmlCollections input = new MetXmlCollections();
            input.setWrapped(Arrays.asList((String) null));
            MetXmlCollectionService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);
            MetXmlCollections result = remote.echo(input);

            // 保留集合容器和集合元素各自的空值语义。
            assertNotNull(result.getWrapped());
            assertEquals(1, result.getWrapped().size());
            assertNull(result.getWrapped().get(0));
        }
    }

    @Test(timeout = 30000)
    public void unwrappedArrayPreservesMultipleElementsAndNil() throws Exception {
        // 启动双端容器，普通 DTO 决定集合在 XML 中的包装方式。
        MetConfiguration incoming = this.configuration(true, "1.1");
        MetConfiguration outgoing = this.configuration(false, "1.1");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlCollectionService.class, value -> value) //
                    .aliasName("soap", "MetXmlCollectionService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlCollectionService> subscription = client.publisher() //
                    .rsfService(MetXmlCollectionService.class) //
                    .aliasName("soap", "MetXmlCollectionService") //
                    .protocol("soap") //
                    .register();

            // 准备本场景的集合内容，通过公开客户端完成一次 SOAP 往返。
            MetXmlCollections input = new MetXmlCollections();
            input.setUnwrapped(new Integer[] { 3, null, 5 });
            MetXmlCollectionService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);
            MetXmlCollections result = remote.echo(input);

            // 保留集合容器和集合元素各自的空值语义。
            assertArrayEquals(new Integer[] { 3, null, 5 }, result.getUnwrapped());
        }
    }

    @Test(timeout = 30000)
    public void unwrappedEnumListUsesItsXmlLexicalValues() throws Exception {
        // 启动双端容器，普通 DTO 决定集合在 XML 中的包装方式。
        MetConfiguration incoming = this.configuration(true, "1.1");
        MetConfiguration outgoing = this.configuration(false, "1.1");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlCollectionService.class, value -> value) //
                    .aliasName("soap", "MetXmlCollectionService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlCollectionService> subscription = client.publisher() //
                    .rsfService(MetXmlCollectionService.class) //
                    .aliasName("soap", "MetXmlCollectionService") //
                    .protocol("soap") //
                    .register();

            // 准备本场景的集合内容，通过公开客户端完成一次 SOAP 往返。
            MetXmlCollections input = new MetXmlCollections();
            input.setStates(List.of(MetXmlState.READY, MetXmlState.SENT));
            MetXmlCollectionService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);
            MetXmlCollections result = remote.echo(input);

            // 保留集合容器和集合元素各自的空值语义。
            assertEquals(List.of(MetXmlState.READY, MetXmlState.SENT), result.getStates());
        }
    }

    @Test(timeout = 30000)
    public void simpleContentPreservesTextAndAttributes() throws Exception {
        // simpleContent 的正文与属性都由 Meteor 注解声明，无需 JAXB。
        MetConfiguration incoming = this.configuration(true, "1.1");
        MetConfiguration outgoing = this.configuration(false, "1.1");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlAmountService.class, value -> value) //
                    .aliasName("soap", "MetXmlAmountService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlAmountService> subscription = client.publisher() //
                    .rsfService(MetXmlAmountService.class) //
                    .aliasName("soap", "MetXmlAmountService") //
                    .protocol("soap") //
                    .register();

            // 从公开客户端发送金额，回调仍处理普通 Java DTO。
            MetXmlAmount input = new MetXmlAmount();
            input.setCurrency("CNY");
            input.setValue(new BigDecimal("123.45"));
            MetXmlAmountService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscription);
            MetXmlAmount result = remote.amount(input);

            // 值按 decimal 还原，币种按无命名空间属性还原。
            assertEquals("CNY", result.getCurrency());
            assertEquals(new BigDecimal("123.45"), result.getValue());
        }
    }

    @Test(timeout = 30000)
    public void externalWsdlBindsTheAnnotatedJavaDtoWithoutGeneratedClasses() throws Exception {
        // 同一 Java 接口通过显式资源对接外部 WSDL，而不使用生成类或 JAXB。
        MetConfiguration incoming = this.configuration(true, "1.1");
        MetConfiguration outgoing = this.configuration(false, "1.1");
        URL wsdl = this.getClass().getResource("/wsdl/met-xml-mapping.wsdl");
        assertNotNull(wsdl);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlAmountService.class, value -> value) //
                    .aliasName("soap", "MetXmlAmountService") //
                    .protocol("soap") //
                    .bindResource(new SoapWsdlResource(wsdl)) //
                    .register();
            MetBindInfo<MetXmlAmountService> subscription = client.publisher() //
                    .rsfService(MetXmlAmountService.class) //
                    .aliasName("soap", "MetXmlAmountService") //
                    .protocol("soap") //
                    .bindResource(new SoapWsdlResource(wsdl)) //
                    .register();

            // 外部契约使用自己的类型名，实例映射仍由 Java DTO 上的注解给出。
            MetXmlAmount input = new MetXmlAmount();
            input.setValue(new BigDecimal("0.125"));
            input.setCurrency("USD");
            MetXmlAmountService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscription);
            MetXmlAmount result = remote.amount(input);
            assertEquals(new BigDecimal("0.125"), result.getValue());
            assertEquals("USD", result.getCurrency());
        }
    }

    @Test(timeout = 30000)
    public void invalidMappingIsRejectedBeforeItsDescriptionIsPublished() throws Exception {
        // 一个 DTO 同时要求 simpleContent 与子元素，服务发布必须拒绝这种冲突。
        MetConfiguration configuration = this.configuration(true, "1.1");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            assertThrows(IllegalArgumentException.class, () -> context.publisher() //
                    .rsfService(MetXmlInvalidService.class, value -> value) //
                    .aliasName("soap", "MetXmlInvalidService") //
                    .protocol("soap") //
                    .register());

            // 失败的发布不得留下可访问的服务声明或描述页面。
            assertNull(context.getServiceInfo(MetXmlInvalidService.class));
            int port = context.getListenAddress("soap").getPort();
            URI address = URI.create("http://127.0.0.1:" + port + "/services/MetXmlInvalidService?wsdl");
            HttpRequest request = HttpRequest.newBuilder(address).GET().build();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, //
                    HttpResponse.BodyHandlers.ofByteArray());
            try {
                assertEquals(404, pending.get(10, TimeUnit.SECONDS).statusCode());
            } finally {
                pending.cancel(true);
            }
        }
    }

    private MetConfiguration configuration(boolean bind, String version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        if (bind) {
            configuration.connector("http") //
                    .bind("127.0.0.1", 0) //
                    .protocol("soap") //
                    .contextPath("/services") //
                    .option("soapVersion", version);
        } else {
            configuration.connector("http") //
                    .protocol("soap") //
                    .contextPath("/services") //
                    .option("soapVersion", version);
        }
        return configuration;
    }

    private MetXmlOrder order() {
        MetXmlOrder order = new MetXmlOrder();
        order.setId("order-42");
        order.setBuyer("Alice");
        order.setDelivery("Bob");
        order.setLines(List.of("first", "second"));
        order.setState(MetXmlState.READY);
        return order;
    }
}
