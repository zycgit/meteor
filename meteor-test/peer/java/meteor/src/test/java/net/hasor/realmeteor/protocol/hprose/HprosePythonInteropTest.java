/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.hprose;
import net.hasor.example.protocol.hprose.Sample;
import net.hasor.example.protocol.hprose.SampleImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.python.PythonSdkPeerProcess;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 官方 Python SDK 与 Meteor 的最小双向 HTTP 互通，不扩展类型及注册发现矩阵。 */
public class HprosePythonInteropTest {

    @Test(timeout = 45000)
    public void pythonSdkCallsMeteor() throws Exception {
        // 通过 bootstrap 启动 Meteor HTTP 监听并发布普通接口。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose").contextPath("/rpc");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(Sample.class, new SampleImpl()) //
                    .protocol("hprose").aliasName("hprose", "sample").register();

            // 官方 Hprose Python 客户端直接调用，断言由该 SDK 进程执行。
            int port = provider.getListenAddress("hprose").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            try (PythonSdkPeerProcess peer = PythonSdkPeerProcess.start("hprose", "consumer", "--target", target)) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 45000)
    public void meteorCallsPythonSdk() throws Exception {
        // Python SDK 自行注册业务方法和建立监听，Meteor 只借用就绪端点。
        try (PythonSdkPeerProcess provider = PythonSdkPeerProcess.start("hprose", "provider")) {
            int port = provider.awaitPort();
            MetConfiguration configuration = new MetConfiguration();
            configuration.connector("http").protocol("hprose").contextPath("/rpc");
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<Sample> service = consumer.publisher().rsfService(Sample.class) //
                        .protocol("hprose").aliasName("hprose", "sample").register();

                // 订阅接口不带本地实现，从正式客户端入口调用真实 Python 服务。
                InterAddress address = new InterAddress("hprose", provider.host(), port, "default");
                Sample remote = consumer.getRsfClient(address).getRemote(service);
                assertEquals("Meteor Hprose 中文", remote.echo("Meteor Hprose 中文"));
            }
        }
    }
}
