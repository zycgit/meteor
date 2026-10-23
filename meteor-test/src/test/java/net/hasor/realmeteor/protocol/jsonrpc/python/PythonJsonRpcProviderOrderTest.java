/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc.python;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Meteor 提供者与独立 Python 标准库消费者使用同一份订单向量；直连不经过注册中心。 */
public class PythonJsonRpcProviderOrderTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();
    private MetContext provider;
    private String endpoint;

    @Before
    public void publishOrderService() throws Exception {
        // 每例从 bootstrap 建立独立容器，只有 HTTP JSON-RPC 业务挂载。
        MetConfiguration incoming = new MetConfiguration().setDefaultTimeout(5000);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        incoming.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc").contextPath("/jsonrpc");
        this.provider = incoming.buildContext();
        this.provider.start();

        // 容器启动后发布原有用户示例接口，没有为跨语言更改业务对象或实现。
        this.provider.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                .aliasName("jsonrpc", "orders") //
                .protocol("jsonrpc") //
                .register();
        int port = this.provider.getListenAddress("jsonrpc").getPort();
        this.endpoint = "http://127.0.0.1:" + port + "/jsonrpc";
    }

    @After
    public void closeProvider() throws Exception {
        if (this.provider != null) {
            this.provider.close();
        }
    }

    @Test(timeout = 40000)
    public void pythonConsumerSavesAndFindsTheSharedOrder() throws Exception {
        // 独立 Python 消费者保存并查询同一笔订单，验证真实网络与服务端存储。
        try (PythonJsonRpcOrderPeer consumer = PythonJsonRpcOrderPeer.consumer(this.endpoint, "order")) {
            consumer.requireSuccess();
        }
    }

    @Test(timeout = 40000)
    public void pythonConsumerPreservesMinimumInt32() throws Exception {
        // 整数下界作为普通 DTO 字段往返，不能转成无符号数。
        try (PythonJsonRpcOrderPeer consumer = PythonJsonRpcOrderPeer.consumer(this.endpoint, "minimum-id")) {
            consumer.requireSuccess();
        }
    }

    @Test(timeout = 40000)
    public void pythonConsumerPreservesMaximumInt32() throws Exception {
        // 整数上界沿用共享向量，独立对端执行精确数值断言。
        try (PythonJsonRpcOrderPeer consumer = PythonJsonRpcOrderPeer.consumer(this.endpoint, "maximum-id")) {
            consumer.requireSuccess();
        }
    }

    @Test(timeout = 40000)
    public void pythonConsumerPreservesAsciiEscaping() throws Exception {
        // 引号、反斜线和标记字符解码后保持原文，不把转义文本作为业务值。
        try (PythonJsonRpcOrderPeer consumer = PythonJsonRpcOrderPeer.consumer(this.endpoint, "ascii-escaping")) {
            consumer.requireSuccess();
        }
    }

    @Test(timeout = 40000)
    public void pythonConsumerPreservesAnEmptyAttachment() throws Exception {
        // JSON 字节字段显式保留空数组对应的空 Base64 文本。
        try (PythonJsonRpcOrderPeer consumer = PythonJsonRpcOrderPeer.consumer(this.endpoint, "empty-attachment")) {
            consumer.requireSuccess();
        }
    }

    @Test(timeout = 40000)
    public void pythonConsumerPreservesEveryOctet() throws Exception {
        // 共享的 0..255 向量经过标准 Base64，包含零字节和全部高位字节。
        try (PythonJsonRpcOrderPeer consumer = PythonJsonRpcOrderPeer.consumer(this.endpoint, "all-octets")) {
            consumer.requireSuccess();
        }
    }

    @Test(timeout = 40000)
    public void pythonConsumerPreservesUnicode() throws Exception {
        // 中英文、希腊文、阿拉伯文和 emoji 由 Python UTF-8 编码后调用 Meteor。
        try (PythonJsonRpcOrderPeer consumer = PythonJsonRpcOrderPeer.consumer(this.endpoint, "unicode-order")) {
            consumer.requireSuccess();
        }
    }

    @Test(timeout = 40000)
    public void pythonConsumerReceivesTheMissingOrderFault() throws Exception {
        // 不存在的订单产生可解析 JSON-RPC 故障，不能把超时或退出当成成功。
        try (PythonJsonRpcOrderPeer consumer = PythonJsonRpcOrderPeer.consumer(this.endpoint, "missing-order")) {
            consumer.requireSuccess();
        }
    }
}
