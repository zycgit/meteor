/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import net.hasor.example.protocol.triple.NamedEcho;
import net.hasor.example.protocol.triple.WireEcho;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

public class TripleCommonNamesTest {
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void annotatedProviderAcceptsTheOriginalWireMethod() throws Exception {
        // 提供者 Java 方法为 repeat，消费者沿用协议上的 echo。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(NamedEcho.class, value -> "provider:" + value) //
                    .aliasName("triple", "demo").register();
            MetBindInfo<WireEcho> service = client.publisher().rsfService(WireEcho.class) //
                    .aliasName("triple", "demo").register();

            // 线上名被还原为提供者原始 Java 方法，再由 Dispatcher 调用。
            WireEcho remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            assertEquals("provider:hello", remote.echo("hello"));
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void annotatedConsumerCallsTheOriginalWireMethod() throws Exception {
        // 提供者不携带注解，消费者以自己的 repeat 方法访问相同契约。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(WireEcho.class, value -> "consumer:" + value) //
                    .aliasName("triple", "demo").register();
            MetBindInfo<NamedEcho> service = client.publisher().rsfService(NamedEcho.class) //
                    .aliasName("triple", "demo").register();

            // 出站查询准备好的对应表，响应仍按消费者 Java 签名解释。
            NamedEcho remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            assertEquals("consumer:hello", remote.repeat("hello"));
        }
    }

    private MetConfiguration configuration(boolean listen) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        var connector = configuration.connector("http").option("httpVersion", 2);
        if (listen) {
            connector.bind("127.0.0.1", 0);
        }
        connector.protocol("triple");
        return configuration;
    }
}
