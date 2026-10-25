/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** 每个外语提供者与单协议 Java 消费者 Main 直接互通。 */
@RunWith(Parameterized.class)
public class MultilingualProviderScenarioTest {
    private final String language;
    private final String protocol;

    public MultilingualProviderScenarioTest(String language, String protocol) {
        this.language = language;
        this.protocol = protocol;
    }

    @Parameterized.Parameters(name = "example/{0}/{1}/direct/provider")
    public static List<Object[]> scenarios() {
        return OrderPeerProcess.languagesAndProtocols();
    }

    @Test(timeout = 120000)
    public void javaConsumerSavesAndFindsTheLanguageOrder() throws Exception {
        // 外语对端自行建立真实 SDK 服务器和业务存储。
        try (OrderPeerProcess provider = OrderPeerProcess.foreignProvider(this.language, this.protocol)) {
            String target = provider.awaitReady("Order provider is ready: ");

            // Meteor 消费者访问对端报告的容器 DNS 和实际监听端口。
            try (OrderPeerProcess consumer = OrderPeerProcess.javaMain(this.protocol, "Consumer", target)) {
                JsonNode result = consumer.awaitResult();

                // 查询结果必须保留 Java 保存的描述和附件，不能只以进程退出作为互通成功。
                OrderPeerProcess.assertOrder(result, "created by " + this.protocol, "bWV0ZW9yLW9yZGVy");
            }
        }
    }
}
