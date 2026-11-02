/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import net.hasor.example.protocol.hessian.HessianDemoService;
import net.hasor.example.protocol.hessian.HessianDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

public class HessianBootstrapTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unicodeTextKeepsItsExactValue() throws Exception {
        // Unicode 字符串在 Hessian 编码中保留原值。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两个容器，再发布并订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 使用独立客户端上下文发起实际网络请求，验证调用结果。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            String message = "Meteor 汉字 🌍 + % /";
            assertEquals(message, remote.echo(message));
        }
    }

    private MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/rpc");
        return configuration;
    }

    private MetConfiguration clientConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.connector("http").protocol("hessian").contextPath("/rpc");
        return configuration;
    }
}
