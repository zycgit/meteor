/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** 每个外语消费者只访问其协议对应的测试自有 Meteor 提供者。 */
@RunWith(Parameterized.class)
public class MultilingualConsumerScenarioTest {
    private final String language;
    private final String protocol;

    public MultilingualConsumerScenarioTest(String language, String protocol) {
        this.language = language;
        this.protocol = protocol;
    }

    @Parameterized.Parameters(name = "example/{0}/{1}/direct/consumer")
    public static List<Object[]> scenarios() {
        return OrderPeerProcess.languagesAndProtocols();
    }

    @Test(timeout = 120000)
    public void languageConsumerSavesAndFindsTheOrder() throws Exception {
        // 从公开入口创建当前协议的 Meteor 提供者，不挂载其它业务协议。
        try (OrderPeerProcess provider = OrderPeerProcess.javaMain(this.protocol, "Provider", null)) {
            String target = provider.awaitReady("Order provider is ready: ");

            // 外语消费者通过提供者的容器 DNS 和实际端口发起网络调用。
            try (OrderPeerProcess consumer = OrderPeerProcess.foreignConsumer(this.language, this.protocol, target)) {
                JsonNode result = consumer.awaitResult();

                // 保存再查询构成一个入门场景，三个字段证明业务对象及二进制附件都已返回。
                String description = "meteor-order";
                String attachment = "TWV0ZW9y";
                if (StringUtils.equals("python", this.language)) {
                    description = "Order from Python";
                } else if (StringUtils.equals("node", this.language)) {
                    description = "Order from Node.js";
                } else if (StringUtils.equals("go", this.language)) {
                    description = "Meteor order";
                    attachment = "aGVsbG8gTWV0ZW9y";
                }
                OrderPeerProcess.assertOrder(result, description, attachment);
            }
        }
    }
}
