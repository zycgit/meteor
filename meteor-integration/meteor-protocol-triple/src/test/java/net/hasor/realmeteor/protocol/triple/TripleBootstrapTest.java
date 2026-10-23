/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import net.hasor.example.protocol.triple.TripleDemoService;
import net.hasor.example.protocol.triple.TripleDemoServiceImpl;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 插件从公开 bootstrap 完成一次基本网络调用。 */
public class TripleBootstrapTest {
    @Test(timeout = 15000)
    public void wrapperUnaryPreservesUnicodeBusinessValues() throws Exception {
        // 配置真实网络端点，出站容器不创建监听。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // Hessian2 wrapper 恢复原始业务 DTO，而不是暴露协议包装对象。
            TripleMessage result = remote.echo(TripleMessage.of("你好 Triple 🌏", 6));
            assertEquals("你好 Triple 🌏", result.getText());
            assertEquals(7, result.getNumber());
        }
    }

    private MetConfiguration configuration(boolean listener) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (listener) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("triple").contextPath("/rpc").option("streamBody", true).option("httpMode", "grpc");
        return configuration;
    }
}
