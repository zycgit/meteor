/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift.python;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.apache.thrift.TApplicationException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.*;

/** Meteor 公开客户端调用官方 Python Processor，不共享编码或业务状态。 */
@RunWith(Parameterized.class)
public class PythonProviderScenarioTest {
    private final String  encoding;
    private final boolean framed;
    private final String  security;

    public PythonProviderScenarioTest(String encoding, boolean framed, String security) {
        this.encoding = encoding;
        this.framed = framed;
        this.security = security;
    }

    @Parameterized.Parameters(name = "python-provider/{0}/framed={1}/security={2}")
    public static Collection<Object[]> parameters() {
        return PythonThriftSettings.parameters();
    }

    @Test(timeout = 30000)
    public void meteorReadsSavedOrderFromPython() throws Exception {
        // Python provider 独占自己的监听端口和官方 Processor，Meteor 只建立出站通道。
        MetConfiguration configuration = PythonThriftSettings.configuration( //
                this.encoding, this.framed, this.security, false);
        try (PythonThriftPeer peer = PythonThriftPeer.provider(this.encoding, this.framed, this.security); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通注解接口，直接传递共享订单和查询 DTO。
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher() //
                    .rsfService(OrderDemoService.class) //
                    .protocol("thrift").aliasName("thrift", "OrderThriftService").register();
            String scheme = PythonThriftSettings.scheme(this.security);
            InterAddress address = new InterAddress(scheme, peer.host(), port, "default");
            OrderDemoService remote = client.getRsfClient(address).getRemote(service);

            // 保存后再查询同一个订单，验证对端存储的实际值及返回 DTO。
            Order expected = new Order();
            expected.setId(42);
            expected.setDescription("Order from Meteor");
            expected.setAttachment(new byte[] { 77, 101, 116, 101, 111, 114 });
            remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = remote.find(query);
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void meteorReadsUnicodeFromPython() throws Exception {
        // Python provider 独占自己的监听端口和官方 Processor，Meteor 只建立出站通道。
        MetConfiguration configuration = PythonThriftSettings.configuration( //
                this.encoding, this.framed, this.security, false);
        try (PythonThriftPeer peer = PythonThriftPeer.provider(this.encoding, this.framed, this.security); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通注解接口，直接传递共享订单和查询 DTO。
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher() //
                    .rsfService(OrderDemoService.class) //
                    .protocol("thrift").aliasName("thrift", "OrderThriftService").register();
            String scheme = PythonThriftSettings.scheme(this.security);
            InterAddress address = new InterAddress(scheme, peer.host(), port, "default");
            OrderDemoService remote = client.getRsfClient(address).getRemote(service);

            // 保存后再查询同一个订单，验证对端存储的实际值及返回 DTO。
            Order expected = new Order();
            expected.setId(42);
            expected.setDescription("Meteor 订单 🙂 <&>");
            expected.setAttachment(new byte[] { 77, 101, 116, 101, 111, 114 });
            remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = remote.find(query);
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void meteorReadsBinaryFromPython() throws Exception {
        // Python provider 独占自己的监听端口和官方 Processor，Meteor 只建立出站通道。
        MetConfiguration configuration = PythonThriftSettings.configuration( //
                this.encoding, this.framed, this.security, false);
        try (PythonThriftPeer peer = PythonThriftPeer.provider(this.encoding, this.framed, this.security); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通注解接口，直接传递共享订单和查询 DTO。
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher() //
                    .rsfService(OrderDemoService.class) //
                    .protocol("thrift").aliasName("thrift", "OrderThriftService").register();
            String scheme = PythonThriftSettings.scheme(this.security);
            InterAddress address = new InterAddress(scheme, peer.host(), port, "default");
            OrderDemoService remote = client.getRsfClient(address).getRemote(service);

            // 保存后再查询同一个订单，验证对端存储的实际值及返回 DTO。
            Order expected = new Order();
            expected.setId(42);
            expected.setDescription("Order from Meteor");
            expected.setAttachment(new byte[] { 0, 1, 127, -128, -1 });
            remote.save(expected);
            OrderQuery query = new OrderQuery();
            query.setId(expected.getId());
            Order found = remote.find(query);
            assertEquals(expected.getId(), found.getId());
            assertEquals(expected.getDescription(), found.getDescription());
            assertArrayEquals(expected.getAttachment(), found.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void meteorReceivesPythonBusinessFailure() throws Exception {
        // Python provider 独占自己的监听端口和官方 Processor，Meteor 只建立出站通道。
        MetConfiguration configuration = PythonThriftSettings.configuration( //
                this.encoding, this.framed, this.security, false);
        try (PythonThriftPeer peer = PythonThriftPeer.provider(this.encoding, this.framed, this.security); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通注解接口，直接传递共享订单和查询 DTO。
            client.start();
            MetBindInfo<OrderDemoService> service = client.publisher() //
                    .rsfService(OrderDemoService.class) //
                    .protocol("thrift").aliasName("thrift", "OrderThriftService").register();
            String scheme = PythonThriftSettings.scheme(this.security);
            InterAddress address = new InterAddress(scheme, peer.host(), port, "default");
            var remote = client.getRsfClient(address);

            // 缺失订单由官方 Processor 返回 INTERNAL_ERROR，不接受连接中断或调用超时代替。
            OrderQuery query = new OrderQuery();
            query.setId(404);
            var pending = remote.asyncInvoke(service, "find", new Class<?>[] { OrderQuery.class }, //
                    new Object[] { query });
            ExecutionException rejected = assertThrows(ExecutionException.class, //
                    () -> pending.getData(5, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof TApplicationException);
            TApplicationException failure = (TApplicationException) rejected.getCause();
            assertEquals(TApplicationException.INTERNAL_ERROR, failure.getType());
            // 官方生成的 Processor 将未声明业务异常映射为固定文案，不能接受本地路由失败。
            assertEquals("Internal error", failure.getMessage());
        }
    }
}
