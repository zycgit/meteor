/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift.python;
import java.util.Collection;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import net.hasor.realmeteor.environment.container.ContainerProcess;

/** 官方 Python Client 调用 Meteor，通过共享订单业务验证每个 TCP 配置。 */
@RunWith(Parameterized.class)
public class PythonConsumerScenarioTest {
    private final String  encoding;
    private final boolean framed;
    private final String  security;

    public PythonConsumerScenarioTest(String encoding, boolean framed, String security) {
        this.encoding = encoding;
        this.framed = framed;
        this.security = security;
    }

    @Parameterized.Parameters(name = "python-consumer/{0}/framed={1}/security={2}")
    public static Collection<Object[]> parameters() {
        return PythonThriftSettings.parameters();
    }

    @Test(timeout = 30000)
    public void pythonConsumerReadsSavedOrder() throws Exception {
        // 公开 bootstrap 选择编码、分帧及认证配置，业务对象复用统一订单实现。
        MetConfiguration configuration = PythonThriftSettings.configuration( //
                this.encoding, this.framed, this.security, true);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后直接发布注解接口，不转换业务对象或绑定生成契约。
            server.start();
            OrderDemoServiceImpl orders = new OrderDemoServiceImpl();
            server.publisher().rsfService(OrderDemoService.class, orders) //
                    .protocol("thrift").aliasName("thrift", "OrderThriftService").register();

            // 保存后的订单可以再次按 ID 查询，由独立 Python SDK 执行当前场景的断言。
            int port = server.getListenAddress("thrift").getPort();
            try (PythonThriftPeer peer = PythonThriftPeer.consumer( //
                    this.encoding, this.framed, this.security, ContainerProcess.runnerHost(), port, "basic")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonConsumerPreservesUnicode() throws Exception {
        // 公开 bootstrap 选择编码、分帧及认证配置，业务对象复用统一订单实现。
        MetConfiguration configuration = PythonThriftSettings.configuration( //
                this.encoding, this.framed, this.security, true);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后直接发布注解接口，不转换业务对象或绑定生成契约。
            server.start();
            OrderDemoServiceImpl orders = new OrderDemoServiceImpl();
            server.publisher().rsfService(OrderDemoService.class, orders) //
                    .protocol("thrift").aliasName("thrift", "OrderThriftService").register();

            // Unicode 描述往返不丢失字符，由独立 Python SDK 执行当前场景的断言。
            int port = server.getListenAddress("thrift").getPort();
            try (PythonThriftPeer peer = PythonThriftPeer.consumer( //
                    this.encoding, this.framed, this.security, ContainerProcess.runnerHost(), port, "unicode")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonConsumerPreservesBinary() throws Exception {
        // 公开 bootstrap 选择编码、分帧及认证配置，业务对象复用统一订单实现。
        MetConfiguration configuration = PythonThriftSettings.configuration( //
                this.encoding, this.framed, this.security, true);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后直接发布注解接口，不转换业务对象或绑定生成契约。
            server.start();
            OrderDemoServiceImpl orders = new OrderDemoServiceImpl();
            server.publisher().rsfService(OrderDemoService.class, orders) //
                    .protocol("thrift").aliasName("thrift", "OrderThriftService").register();

            // 二进制附件不发生字符串转换，由独立 Python SDK 执行当前场景的断言。
            int port = server.getListenAddress("thrift").getPort();
            try (PythonThriftPeer peer = PythonThriftPeer.consumer( //
                    this.encoding, this.framed, this.security, ContainerProcess.runnerHost(), port, "bytes")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonConsumerReceivesBusinessFailure() throws Exception {
        // 公开 bootstrap 选择编码、分帧及认证配置，业务对象复用统一订单实现。
        MetConfiguration configuration = PythonThriftSettings.configuration( //
                this.encoding, this.framed, this.security, true);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后直接发布注解接口，不转换业务对象或绑定生成契约。
            server.start();
            OrderDemoServiceImpl orders = new OrderDemoServiceImpl();
            server.publisher().rsfService(OrderDemoService.class, orders) //
                    .protocol("thrift").aliasName("thrift", "OrderThriftService").register();

            // 不存在的订单返回业务异常而非传输失败，由独立 Python SDK 执行当前场景的断言。
            int port = server.getListenAddress("thrift").getPort();
            try (PythonThriftPeer peer = PythonThriftPeer.consumer( //
                    this.encoding, this.framed, this.security, ContainerProcess.runnerHost(), port, "failure")) {
                peer.requireSuccess();
            }
        }
    }
}
