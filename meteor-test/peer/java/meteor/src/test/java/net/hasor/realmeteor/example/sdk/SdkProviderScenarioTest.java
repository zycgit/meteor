/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.sdk;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Meteor 消费者分别调用各 SDK 提供者的独立测试入口。 */
@RunWith(Parameterized.class)
public class SdkProviderScenarioTest {
    private final String protocol;
    private final String profile;
    private final String sdkVersion;

    public SdkProviderScenarioTest(String protocol, String profile, String sdkVersion) {
        this.protocol = protocol;
        this.profile = profile;
        this.sdkVersion = sdkVersion;
    }

    @Parameterized.Parameters(name = "sdk/java/{0}/{2}/{1}/Meteor-to-SDK")
    public static List<Object[]> protocols() throws Exception {
        return SdkPeerProcess.compatibilityCases("provider");
    }

    @Test(timeout = 180000)
    public void meteorConsumerSavesAndFindsOrderOnSdk() throws Exception {
        // SDK 使用独立类路径创建自身服务端，启动前确认没有引入 Meteor 内核。
        try (SdkPeerProcess provider = SdkPeerProcess.sdkVersionMain(this.protocol, this.profile, "Provider", null)) {
            String target = provider.awaitReady("SDK order provider is ready: ");

            // Meteor 单协议消费者通过另一容器的 DNS 和实际端口调用 SDK 服务。
            try (SdkPeerProcess consumer = SdkPeerProcess.meteorMain(this.protocol, "Consumer", target)) {
                JsonNode order = consumer.awaitOrder();

                // 业务结果包含 Meteor 写入的描述及二进制附件，验证两次真实网络调用。
                assertEquals(this.sdkVersion, 1001, order.required("id").intValue());
                assertEquals("created by " + this.protocol, order.required("description").textValue());
                assertEquals("bWV0ZW9yLW9yZGVy", order.required("attachment").textValue());
            }
        }
    }
}
