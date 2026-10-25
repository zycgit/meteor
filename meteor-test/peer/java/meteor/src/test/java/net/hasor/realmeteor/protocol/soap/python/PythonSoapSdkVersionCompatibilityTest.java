/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.txt.
 */
package net.hasor.realmeteor.protocol.soap.python;
import java.net.URI;
import java.nio.file.Path;
import java.util.Base64;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** 两代 Zeep 消费者和当前 soapfish2 提供者使用各自 SDK 的 XML 映射。 */
@RunWith(Parameterized.class)
public class PythonSoapSdkVersionCompatibilityTest {
    private final String version;
    private final String direction;

    public PythonSoapSdkVersionCompatibilityTest(String sdk, String version, String direction) {
        this.version = version;
        this.direction = direction;
    }

    @Parameterized.Parameters(name = "sdk/python/soap/{0}@{1}/soap11/{2}")
    public static Object[][] versions() {
        return new Object[][] { // Zeep 只提供消费者；soapfish2 独立提供真实 SOAP 服务。
                { "zeep", "3.4.0", "SDK-to-Meteor" }, //
                { "zeep", "4.3.3", "SDK-to-Meteor" }, //
                { "soapfish2", "0.7.4", "Meteor-to-SDK" } };
    }

    @Test(timeout = 60000)
    public void exchangesOrderUsingTheSelectedSdkVersion() throws Exception {
        // 每个发行版只验证它实际提供的一个角色，版本由对端加载的发行包再次核验。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        var connector = configuration.connector("http");
        boolean sdkConsumer = StringUtils.equals(this.direction, "SDK-to-Meteor");
        if (sdkConsumer) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        connector.protocol("soap").contextPath("/soap").addressScheme("http");

        if (sdkConsumer) {
            // Meteor 自动生成在线 WSDL，Zeep 自己下载并调用，不借用 Java XML 编码器。
            try (MetContext provider = configuration.buildContext(); //
                 PythonSoapPeer consumer = PythonSoapPeer.versionConsumer(this.version)) {
                provider.start();
                provider.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                        .name("orders").protocol("soap").aliasName("soap", "orders").register();
                InterAddress address = provider.getListenAddress("soap");
                URI wsdl = new URI("http", null, address.getHost(), address.getPort(), "/soap/orders", "wsdl", null);

                // 同一笔订单同时包含 Unicode 文本和不能按 UTF-8 文本处理的原始字节。
                ObjectMapper mapper = new ObjectMapper();
                Order expected = order();
                JsonNode result = consumer.roundTrip(wsdl, mapper.valueToTree(expected));
                assertEquals(expected.getId(), result.required("id").intValue());
                assertEquals(expected.getDescription(), result.required("description").textValue());
                byte[] attachment = Base64.getDecoder().decode(result.required("attachment").textValue());
                assertArrayEquals(expected.getAttachment(), attachment);
            }
            return;
        }

        // soapfish2 自己生成契约及处理 SOAP 请求，Meteor 绑定该标准契约建立出站服务。
        try (PythonSoapPeer provider = PythonSoapPeer.provider(); //
             MetContext consumer = configuration.buildContext()) {
            consumer.start();
            var contract = Path.of(System.getProperty("python.soap.contract")).toUri().toURL();
            var service = consumer.publisher().rsfService(OrderDemoService.class) //
                    .name("orders").protocol("soap").aliasName("soap", "orders") //
                    .bindResource("soap", new SoapWsdlResource(contract)).register();
            OrderDemoService remote = consumer.getRsfClient(provider.address("default")).getRemote(service);

            // 两次真实调用确认保存状态以及 XML 字符和 base64Binary 映射没有变化。
            Order expected = order();
            remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order actual = remote.find(query);
            assertEquals(expected.getId(), actual.getId());
            assertEquals(expected.getDescription(), actual.getDescription());
            assertArrayEquals(expected.getAttachment(), actual.getAttachment());
        }
    }

    private static Order order() {
        Order order = new Order();
        order.setId(1042);
        order.setDescription("SDK 版本订单 🙂 <&>");
        order.setAttachment(new byte[] { 0, 1, 127, (byte) 128, (byte) 255 });
        return order;
    }
}
