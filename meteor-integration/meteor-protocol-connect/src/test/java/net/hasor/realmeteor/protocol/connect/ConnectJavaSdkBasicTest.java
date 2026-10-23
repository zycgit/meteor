/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.peer.protocol.connect.ConnectSdkClient;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 同进程基础可用性：官方 JVM SDK 客户端通过真实 HTTP 调用 Meteor。 */
public class ConnectJavaSdkBasicTest {
    @Test(timeout = 30000)
    public void officialJavaSdkCallsPublishedJsonService() throws Exception {
        // 只挂载当前被测协议，不能以其它协议成功代替本协议可用。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("connect").contextPath("/rpc").option("streamBody", true);
        ConnectDemoServiceImpl implementation = new ConnectDemoServiceImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, implementation) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .serialize("Json").register();

            // 在当前 JUnit 进程直接调用官方 SDK，仍通过真实 HTTP 监听端口往返。
            int port = server.getListenAddress("connect").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            String expected = "Java SDK connect 中文 🙂";
            String method = "meteor.connect.demo.ConnectDemoService/echo";
            String result = ConnectSdkClient.invoke(target, method, expected);
            assertEquals(expected + "\n7", result);
            assertEquals(1, implementation.getCalls());
        }
    }
}

