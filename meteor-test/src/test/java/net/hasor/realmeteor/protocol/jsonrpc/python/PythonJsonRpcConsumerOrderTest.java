/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc.python;
import java.util.concurrent.ExecutionException;
import net.hasor.example.matrix.OrderMatrixVectors;
import net.hasor.example.order.Order;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.jsonrpc.JsonRpcException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Meteor 消费者直接调用独立 Python 标准库服务端，业务存储和 HTTP 均由 Python 持有。 */
public class PythonJsonRpcConsumerOrderTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();
    private PythonJsonRpcOrderPeer provider;
    private MetContext consumer;
    private MetClient client;
    private MetBindInfo<OrderDemoService> subscription;
    private OrderDemoService remote;

    @Before
    public void subscribeToPythonOrderService() throws Exception {
        // Python 自己绑定动态端口；Meteor 仅从启动通知取得直连地址。
        this.provider = PythonJsonRpcOrderPeer.provider();
        int port = this.provider.awaitPort();
        MetConfiguration outgoing = new MetConfiguration().setDefaultTimeout(5000);
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
        outgoing.connector("http").protocol("jsonrpc").contextPath("/jsonrpc");
        this.consumer = outgoing.buildContext();
        this.consumer.start();

        // 启动后订阅用户示例的原有接口，消费者不监听也不发布本地业务实现。
        this.subscription = this.consumer.publisher().rsfService(OrderDemoService.class) //
                .aliasName("jsonrpc", "orders") //
                .protocol("jsonrpc") //
                .register();
        InterAddress address = new InterAddress("jsonrpc", "127.0.0.1", port, "default");
        this.client = this.consumer.getRsfClient(address);
        this.remote = this.client.getRemote(this.subscription);
    }

    @After
    public void closeApplications() throws Exception {
        // 当前测试拥有两端资源；关闭消费者后结束独立 Python 提供者。
        try {
            if (this.consumer != null) {
                this.consumer.close();
            }
        } finally {
            if (this.provider != null) {
                this.provider.close();
            }
        }
    }

    @Test(timeout = 40000)
    public void meteorConsumerSavesAndFindsTheSharedOrder() throws Exception {
        // 保存与查询共同验证 Python 服务中的同一份订单存储。
        Order order = OrderMatrixVectors.order("order");
        this.remote.save(order);
        OrderQuery query = new OrderQuery();
        query.setId(order.getId());
        Order found = this.remote.find(query);

        // 普通 DTO、整数、文本和二进制字段都经由独立 HTTP 服务返回。
        assertEquals(order.getId(), found.getId());
        assertEquals(order.getDescription(), found.getDescription());
        assertArrayEquals(order.getAttachment(), found.getAttachment());
    }

    @Test(timeout = 40000)
    public void meteorConsumerPreservesMinimumInt32() throws Exception {
        // 共享向量中的 int32 下界由 Python JSON 数值返回为 Java int。
        Order order = OrderMatrixVectors.order("minimum-id");
        assertEquals(Integer.MIN_VALUE, this.remote.save(order).getId());
    }

    @Test(timeout = 40000)
    public void meteorConsumerPreservesMaximumInt32() throws Exception {
        // 共享向量中的 int32 上界保持精确数值。
        Order order = OrderMatrixVectors.order("maximum-id");
        assertEquals(Integer.MAX_VALUE, this.remote.save(order).getId());
    }

    @Test(timeout = 40000)
    public void meteorConsumerPreservesAsciiEscaping() throws Exception {
        // 引号、反斜线和标记字符经过两端 JSON 编解码后保持原文。
        Order order = OrderMatrixVectors.order("ascii-escaping");
        assertEquals(order.getDescription(), this.remote.save(order).getDescription());
    }

    @Test(timeout = 40000)
    public void meteorConsumerPreservesAnEmptyAttachment() throws Exception {
        // JSON 字段的空 Base64 文本恢复为显式零长度 byte[]。
        Order order = OrderMatrixVectors.order("empty-attachment");
        assertArrayEquals(new byte[0], this.remote.save(order).getAttachment());
    }

    @Test(timeout = 40000)
    public void meteorConsumerPreservesEveryOctet() throws Exception {
        // 完整 0..255 向量由 Python 校验 Base64 后原样返回。
        Order order = OrderMatrixVectors.order("all-octets");
        assertArrayEquals(order.getAttachment(), this.remote.save(order).getAttachment());
    }

    @Test(timeout = 40000)
    public void meteorConsumerPreservesUnicode() throws Exception {
        // Python 使用 UTF-8 响应多语言和 emoji，Java 代理保持原始文本。
        Order order = OrderMatrixVectors.order("unicode-order");
        assertEquals(order.getDescription(), this.remote.save(order).getDescription());
    }

    @Test(timeout = 40000)
    public void meteorConsumerReceivesTheMissingOrderFault() throws Exception {
        // 通过公开调用入口保留对端 JSON-RPC 故障，而非接受任何失败为成功。
        OrderQuery query = new OrderQuery();
        query.setId(404);
        Class<?>[] parameters = { OrderQuery.class };
        Object[] arguments = { query };
        ExecutionException failure = assertThrows(ExecutionException.class, //
                () -> this.client.syncInvoke(this.subscription, "find", parameters, arguments));

        // 标准库服务端声明的业务错误码与文本必须到达 Meteor 调用者。
        assertTrue(failure.getCause() instanceof JsonRpcException);
        JsonRpcException error = (JsonRpcException) failure.getCause();
        assertEquals(-32001, error.getCode());
        assertEquals("Order not found: 404", error.getMessage());
    }

}
