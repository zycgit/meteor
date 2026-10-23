/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.order.Order;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 同一个订单契约经 RSF 通道选择 Java、JSON、Hessian 或 Hprose 编码。 */
public class SerializationScenarioTest {
    @Test
    public void javaPreservesPojoUnicodeAndBinaryFields() throws Exception {
        // 先启动点对点两端，再使用大小写不同的 SPI 名称选择同一序列化器。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("network-observer", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("rsf").serialize("java").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").serialize("JAVA").register();
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);

            // 普通对象的 Unicode 和二进制字段经过真实网络与序列化链路。
            Order order = new Order();
            order.setId(42);
            order.setDescription("订单🙂");
            order.setAttachment(new byte[] { 0, 1, -1 });
            Order result = remote.save(order);
            assertNotSame(order, result);
            assertEquals(42, result.getId());
            assertEquals("订单🙂", result.getDescription());
            assertArrayEquals(order.getAttachment(), result.getAttachment());
            assertEquals(1, received.get());
            assertSame(server.getSerializeCoder("java"), server.getSerializeCoder("JAVA"));
        }
    }

    @Test
    public void javaPreservesANullArgumentAndResult() throws Exception {
        // 先启动点对点两端，再使用大小写不同的 SPI 名称选择同一序列化器。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OrderDemoService.class, new NullableOrderService()) //
                    .protocol("rsf").serialize("java").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").serialize("JAVA").register();
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);

            // 接口允许业务实现处理空参数，此场景同时观察空结果的远程传递。
            assertNull(remote.save(null));
        }
    }

    @Test
    public void javaPreservesNullablePojoFields() throws Exception {
        // 先启动点对点两端，再使用大小写不同的 SPI 名称选择同一序列化器。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("rsf").serialize("java").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").serialize("JAVA").register();
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);

            // 可空属性保持为空，不被编码器替换为空字符串或空字节数组。
            Order order = new Order();
            order.setId(42);
            Order result = remote.save(order);
            assertEquals(42, result.getId());
            assertNull(result.getDescription());
            assertNull(result.getAttachment());
        }
    }

    @Test
    public void jsonPreservesPojoUnicodeAndBinaryFields() throws Exception {
        // 先启动点对点两端，再使用大小写不同的 SPI 名称选择同一序列化器。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("network-observer", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("rsf").serialize("json").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").serialize("JSON").register();
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);

            // 普通对象的 Unicode 和二进制字段经过真实网络与序列化链路。
            Order order = new Order();
            order.setId(42);
            order.setDescription("订单🙂");
            order.setAttachment(new byte[] { 0, 1, -1 });
            Order result = remote.save(order);
            assertNotSame(order, result);
            assertEquals(42, result.getId());
            assertEquals("订单🙂", result.getDescription());
            assertArrayEquals(order.getAttachment(), result.getAttachment());
            assertEquals(1, received.get());
            assertSame(server.getSerializeCoder("json"), server.getSerializeCoder("JSON"));
        }
    }

    @Test
    public void jsonPreservesANullArgumentAndResult() throws Exception {
        // 先启动点对点两端，再使用大小写不同的 SPI 名称选择同一序列化器。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OrderDemoService.class, new NullableOrderService()) //
                    .protocol("rsf").serialize("json").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").serialize("JSON").register();
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);

            // 接口允许业务实现处理空参数，此场景同时观察空结果的远程传递。
            assertNull(remote.save(null));
        }
    }

    @Test
    public void jsonPreservesNullablePojoFields() throws Exception {
        // 先启动点对点两端，再使用大小写不同的 SPI 名称选择同一序列化器。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("rsf").serialize("json").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").serialize("JSON").register();
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);

            // 可空属性保持为空，不被编码器替换为空字符串或空字节数组。
            Order order = new Order();
            order.setId(42);
            Order result = remote.save(order);
            assertEquals(42, result.getId());
            assertNull(result.getDescription());
            assertNull(result.getAttachment());
        }
    }

    @Test
    public void hessianPreservesPojoUnicodeAndBinaryFields() throws Exception {
        // 先启动点对点两端，再使用大小写不同的 SPI 名称选择同一序列化器。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("network-observer", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("rsf").serialize("hessian").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").serialize("HESSIAN").register();
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);

            // 普通对象的 Unicode 和二进制字段经过真实网络与序列化链路。
            Order order = new Order();
            order.setId(42);
            order.setDescription("订单🙂");
            order.setAttachment(new byte[] { 0, 1, -1 });
            Order result = remote.save(order);
            assertNotSame(order, result);
            assertEquals(42, result.getId());
            assertEquals("订单🙂", result.getDescription());
            assertArrayEquals(order.getAttachment(), result.getAttachment());
            assertEquals(1, received.get());
            assertSame(server.getSerializeCoder("hessian"), server.getSerializeCoder("HESSIAN"));
        }
    }

    @Test
    public void hessianPreservesANullArgumentAndResult() throws Exception {
        // 先启动点对点两端，再使用大小写不同的 SPI 名称选择同一序列化器。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OrderDemoService.class, new NullableOrderService()) //
                    .protocol("rsf").serialize("hessian").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").serialize("HESSIAN").register();
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);

            // 接口允许业务实现处理空参数，此场景同时观察空结果的远程传递。
            assertNull(remote.save(null));
        }
    }

    @Test
    public void hessianPreservesNullablePojoFields() throws Exception {
        // 先启动点对点两端，再使用大小写不同的 SPI 名称选择同一序列化器。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("rsf").serialize("hessian").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").serialize("HESSIAN").register();
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);

            // 可空属性保持为空，不被编码器替换为空字符串或空字节数组。
            Order order = new Order();
            order.setId(42);
            Order result = remote.save(order);
            assertEquals(42, result.getId());
            assertNull(result.getDescription());
            assertNull(result.getAttachment());
        }
    }

    @Test
    public void hprosePreservesPojoUnicodeAndBinaryFields() throws Exception {
        // 先启动点对点两端，再使用大小写不同的 SPI 名称选择同一序列化器。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            AtomicInteger received = new AtomicInteger();
            server.publisher().bindFilter("network-observer", (request, response, chain) -> {
                received.incrementAndGet();
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("rsf").serialize("hprose").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").serialize("HPROSE").register();
            OrderDemoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);

            // 普通对象的 Unicode 和二进制字段经过真实网络与序列化链路。
            Order order = new Order();
            order.setId(42);
            order.setDescription("订单🙂");
            order.setAttachment(new byte[] { 0, 1, -1 });
            Order result = remote.save(order);
            assertNotSame(order, result);
            assertEquals(42, result.getId());
            assertEquals("订单🙂", result.getDescription());
            assertArrayEquals(order.getAttachment(), result.getAttachment());
            assertEquals(1, received.get());
            assertSame(server.getSerializeCoder("hprose"), server.getSerializeCoder("HPROSE"));
        }
    }

}
