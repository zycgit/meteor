/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.example.ConsumerMain;
import net.hasor.example.OrderClient;
import net.hasor.example.ProviderMain;
import net.hasor.example.services.InventoryService;
import net.hasor.example.services.Order;
import net.hasor.example.services.OrderService;
import net.hasor.meteor.address.InterAddress;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HasorExampleTest {
    @Test
    void loadsExampleAddressBookFromClasspath() throws Throwable {
        // 主配置只指向独立地址本，类路径资源可初始化两个服务的地址池。
        Properties overrides = new Properties();
        overrides.setProperty("meteor.connectors.rsf.port", "0");
        try (AppContext app = Hasor.create().registerShutdownHook(false) // 使用演示主配置中的地址本位置。
                .mainSettingWith("hasor-consumer.properties").loadSettings(overrides).addModules(new MeteorModule(), new ConsumerMain()).build()) {
            MetContext context = app.getInstance(MetContext.class);
            String orderId = context.getServiceInfo(OrderService.class).getBindID();
            String inventoryId = context.getServiceInfo(InventoryService.class).getBindID();
            List<InterAddress> expected = List.of(new InterAddress("rsf://127.0.0.1:28760/example"));
            assertEquals(expected, context.getUpdater().queryAllAddresses(orderId));
            assertEquals(expected, context.getUpdater().queryAllAddresses(inventoryId));
        }
    }

    @Test
    void queriesOrderAndInventoryWithExampleConfiguration() throws Throwable {
        // 使用演示入口相同的配置和 Module，测试只覆盖端口为随机值，避免占用演示端口。
        Properties providerSettings = new Properties();
        providerSettings.setProperty("meteor.connectors.rsf.port", "0");
        try (AppContext provider = Hasor.create().registerShutdownHook(false).mainSettingWith("hasor-provider.properties").loadSettings(providerSettings).addModules(new MeteorModule(), new ProviderMain()).build()) {
            String address = provider.getInstance(MetContext.class).getListenAddress("rsf").toString();
            Properties consumerSettings = new Properties();
            consumerSettings.setProperty("meteor.connectors.rsf.port", "0");
            Path book = Files.createTempFile("example-address-book-", ".xml");
            String xml = """
                    <address-book>
                      <service group="hasor-demo" name="%s" version="1.0">
                        <address>%s</address>
                      </service>
                      <service group="hasor-demo" name="%s" version="1.0">
                        <address>%s</address>
                      </service>
                    </address-book>
                    """.formatted(OrderService.class.getName(), address, InventoryService.class.getName(), address);
            Files.writeString(book, xml);
            consumerSettings.setProperty("meteor.address-book", book.toUri().toString());

            // 普通业务 Bean 的两个接口均由容器注入，订单 DTO 和库存查询分别经过真实 RPC。
            try (AppContext consumer = Hasor.create().registerShutdownHook(false).mainSettingWith("hasor-consumer.properties").loadSettings(consumerSettings).addModules(new MeteorModule(), new ConsumerMain()).build()) {
                assertEquals(book.toUri().toString(), consumer.getSettings().getString("meteor.address-book"));
                MetContext meteor = consumer.getInstance(MetContext.class);
                String serviceId = meteor.getServiceInfo(OrderService.class).getBindID();
                assertEquals(List.of(new InterAddress(address)), meteor.getUpdater().queryAllAddresses(serviceId));
                OrderClient client = consumer.getInstance(OrderClient.class);
                Order order = client.getOrder("ORDER-1001");
                assertEquals("ORDER-1001", order.getOrderId());
                assertEquals("COFFEE-001", order.getSku());
                assertEquals(2, order.getQuantity());
                assertEquals(new BigDecimal("59.80"), order.getTotalAmount());
                assertEquals("PAID", order.getStatus());
                assertEquals(42, client.availableStock(order.getSku()));
            }
        }
    }
}
