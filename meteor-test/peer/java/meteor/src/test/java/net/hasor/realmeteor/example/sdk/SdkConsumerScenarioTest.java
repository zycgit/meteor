/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.sdk;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** 每个 SDK 消费者独立调用相同协议的 Meteor 测试提供者。 */
@RunWith(Parameterized.class)
public class SdkConsumerScenarioTest {
    private final String protocol;
    private final String profile;
    private final String sdkVersion;

    public SdkConsumerScenarioTest(String protocol, String profile, String sdkVersion) {
        this.protocol = protocol;
        this.profile = profile;
        this.sdkVersion = sdkVersion;
    }

    @Parameterized.Parameters(name = "sdk/java/{0}/{2}/{1}/SDK-to-Meteor")
    public static List<Object[]> protocols() throws Exception {
        return SdkPeerProcess.compatibilityCases("consumer");
    }

    @Test(timeout = 180000)
    public void sdkConsumerSavesAndFindsOrderOnMeteor() throws Exception {
        // Meteor 提供者在独立常驻容器中绑定系统分配的端口。
        try (SdkPeerProcess provider = SdkPeerProcess.meteorMain(this.protocol, "Provider", null)) {
            String target = provider.awaitReady("Order provider is ready: ");

            // SDK 使用自己的独立类路径运行测试 Main，保存和查询都经真实网络进行。
            try (SdkPeerProcess consumer = SdkPeerProcess.sdkVersionMain(this.protocol, this.profile, "Consumer", target)) {
                JsonNode order = consumer.awaitOrder();

                // 查询内容必须来自当前 SDK 刚保存的订单，不能仅凭进程退出判定成功。
                assertEquals(this.sdkVersion, 1001, order.required("id").intValue());
                String protocolName = StringUtils.equals("grpcweb", this.protocol) ? "grpc-web" : this.protocol;
                assertEquals("created by " + protocolName + "-sdk", order.required("description").textValue());
                assertEquals("bWV0ZW9yLW9yZGVy", order.required("attachment").textValue());
            }
        }
    }
}
