/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.compatibility.go;
import java.net.URI;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.echo.UuidEchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.golang.GoPeerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Thrift 0.18 新增 UUID 线类型，只在具备该能力的 SDK 发布线上验证。 */
@RunWith(Parameterized.class)
public class GoThriftUuidSdkVersionCompatibilityTest {
    private final String version;
    private final String direction;

    public GoThriftUuidSdkVersionCompatibilityTest(String version, String direction) {
        this.version = version;
        this.direction = direction;
    }

    @Parameterized.Parameters(name = "sdk/go/thrift/Apache-Thrift@{0}/uuid-binary-framed/{1}")
    public static Object[][] versions() {
        return new Object[][] {
                { "0.18.1", "SDK-to-Meteor" },
                { "0.18.1", "Meteor-to-SDK" },
                { "0.25.0", "SDK-to-Meteor" },
                { "0.25.0", "Meteor-to-SDK" }
        };
    }

    @Test(timeout = 45000)
    public void nativeUuidWireTypeRoundTrip() throws Exception {
        // 沿用测试自有的普通 Java 注解接口，UUID 不转换成字符串或二进制别名。
        boolean sdkConsumer = StringUtils.equals("SDK-to-Meteor", this.direction);
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.setDefaultTimeout(10000);
        var connector = configuration.connector("tcp");
        if (sdkConsumer) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        connector.protocol("thrift").option("encoding", "binary").option("framed", true);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            UuidEchoService implementation = sdkConsumer ? value -> value : null;
            var binding = context.publisher().rsfService(UuidEchoService.class, implementation) //
                    .protocol("thrift").aliasName("thrift", "Echo").register();
            String executable = "/opt/meteor-peer/go/compatibility/thrift-" + this.version + "-peer";
            String target = sdkConsumer ? context.getListenAddress("thrift").getHostPort() : null;
            String role = sdkConsumer ? "client" : "server";

            // 官方 SDK 直接读写 T_UUID，分别验证两个真实网络方向。
            try (GoPeerProcess peer = GoPeerProcess.startBinary(executable, "thrift-binary-framed", role, //
                    "plain", "uuid", target)) {
                if (sdkConsumer) {
                    JsonNode outcome = peer.awaitResult();
                    assertTrue(outcome.toString(), outcome.path("ok").asBoolean());
                } else {
                    URI endpoint = URI.create(peer.awaitReady());
                    InterAddress address = new InterAddress("thrift://" + endpoint.getAuthority() + "/default");
                    UuidEchoService remote = context.getRsfClient(address).getRemote(binding);
                    UUID value = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
                    assertEquals(value, remote.echoUuid(value));
                }
            }
        }
    }
}
