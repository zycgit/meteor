/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift.python;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** 官方 Python Thrift 不同版本通过同一订单契约与 Meteor 双向互通。 */
@RunWith(Parameterized.class)
public class PythonThriftSdkVersionCompatibilityTest {
    private final String version;
    private final String encoding;
    private final String direction;

    public PythonThriftSdkVersionCompatibilityTest(String version, String encoding, String direction) {
        this.version = version;
        this.encoding = encoding;
        this.direction = direction;
    }

    @Parameterized.Parameters(name = "sdk/python/thrift/Apache-Thrift@{0}/{1}-framed/{2}")
    public static Collection<Object[]> parameters() {
        // 选择三个发行阶段；每个版本、编码和调用方向分别生成独立测试结果。
        List<Object[]> parameters = new ArrayList<>();
        for (String version : List.of("0.13.0", "0.16.0", "0.25.0")) {
            for (String encoding : List.of("binary", "compact", "json")) {
                parameters.add(new Object[] { version, encoding, "SDK-to-Meteor" });
                parameters.add(new Object[] { version, encoding, "Meteor-to-SDK" });
            }
        }
        return parameters;
    }

    @Test(timeout = 30000)
    public void exchangesOrderWithSdkVersion() throws Exception {
        // 每个参数实例只执行一个调用方向，提供者和消费者保留各自独立生命周期。
        boolean sdkConsumer = StringUtils.equals(this.direction, "SDK-to-Meteor");
        MetConfiguration configuration = PythonThriftSettings.configuration(this.encoding, true, "plain", sdkConsumer);
        if (sdkConsumer) {
            // 通过公开 bootstrap 创建 Meteor 提供者，协议按当前编码发布。
            try (MetContext server = configuration.buildContext()) {
                server.start();
                OrderDemoServiceImpl orders = new OrderDemoServiceImpl();
                server.publisher().rsfService(OrderDemoService.class, orders) //
                        .protocol("thrift").aliasName("thrift", "OrderThriftService").register();

                // 指定发行版保存并查询含 Unicode 描述和原始二进制附件的同一个订单。
                String host = ContainerProcess.runnerHost();
                int port = server.getListenAddress("thrift").getPort();
                try (PythonThriftPeer peer = PythonThriftPeer.versionConsumer( //
                        this.version, this.encoding, host, port)) {
                    peer.requireSuccess();
                }
            }
            return;
        }

        // 每个发行版独立启动官方 Processor，Meteor 仅创建出站连接。
        try (PythonThriftPeer peer = PythonThriftPeer.versionProvider(this.version, this.encoding); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher() //
                    .rsfService(OrderDemoService.class) //
                    .protocol("thrift").aliasName("thrift", "OrderThriftService").register();

            // 使用同一业务接口调用官方服务，地址来自当前独立进程的实际监听端口。
            InterAddress address = new InterAddress("thrift", peer.host(), port, "default");
            OrderDemoService remote = client.getRsfClient(address).getRemote(service);
            Order expected = new Order();
            expected.setId(104);
            expected.setDescription("Meteor 版本兼容订单 🙂 <&>");
            expected.setAttachment(new byte[] { 0, 1, 127, -128, -1 });
            remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = remote.find(query);

            // 同一次保存、查询往返保留订单标识、Unicode 文本和二进制值。
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }
}
