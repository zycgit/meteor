/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import net.hasor.example.protocol.soap.NamedEcho;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

public class SoapCommonNamesScenarioTest {
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void soap12UsesCommonNamesAndDispatchesTheOriginalJavaMethod() throws Exception {
        // 协议挂载声明 SOAP 1.2，业务接口通过公共注解生成两端的契约。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("soap");
        incoming.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services").option("soapVersion", "1.2");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http") //
                .protocol("soap").contextPath("/services").option("soapVersion", "1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器，再发布和订阅同一份 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(NamedEcho.class, value -> "named:" + value) //
                    .aliasName("soap", "NamedEcho").protocol("soap").register();
            MetBindInfo<NamedEcho> subscription = client.publisher().rsfService(NamedEcho.class) //
                    .aliasName("soap", "NamedEcho").protocol("soap").register();

            // 在线上使用 Echo/message/answer，在 Java 中仍调用 repeat。
            NamedEcho remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(subscription);
            assertEquals("named:hello", remote.repeat("hello"));
        }
    }
}
