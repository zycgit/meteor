/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.hessian;
import net.hasor.example.protocol.hessian.HessianInteropDemo;
import net.hasor.example.protocol.hessian.HessianInteropDemoImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.test.nxn.NxnRpc;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.python.PythonSdkPeerProcess;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** python-hessian 的公开能力是 HTTP 客户端，本类只记录 Python 到 Meteor 方向。 */
@NxnRpc(name = "hessian", version = "2")
public class HessianPythonInteropTest {

    @Test
    @Timeout(value = 45000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonSdkCallsMeteor() throws Exception {
        // 从 bootstrap 发布 Hessian 2 服务，不使用 Java SDK 代理转发 Python 的调用。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hessian").contextPath("/hessian").option("version", 2);
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .protocol("hessian").aliasName("hessian", "demo").serialize("Hessian").register();

            // 独立 python-hessian 代理负责 HTTP 调用和响应断言。
            int port = provider.getListenAddress("hessian").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/hessian/demo";
            try (PythonSdkPeerProcess peer = PythonSdkPeerProcess.start("hessian", "--target", target)) {
                peer.requireSuccess();
            }
        }
    }
}
