/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.protocol.grpc.web;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.grpc.web.GrpcWebService;
import net.hasor.example.protocol.grpc.web.GrpcWebServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.node.NodeRpcPeer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** 两代官方 grpc-web 客户端在真实 Chromium 中分别使用二进制和文本模式。 */
@RunWith(Parameterized.class)
public class GrpcWebSdkVersionScenarioTest {
    private final String profile;
    private final String format;

    public GrpcWebSdkVersionScenarioTest(String sdk, String version, String profile, String direction, String format) {
        this.profile = profile;
        this.format = format;
    }

    @Parameterized.Parameters(name = "sdk/browser/grpc-web/{0}@{1}/{2}-{4}/{3}")
    public static List<Object[]> versions() throws Exception {
        List<Object[]> cases = new ArrayList<>();
        for (Object[] profile : NodeRpcPeer.compatibilityCases("grpc-web")) {
            for (String format : List.of("binary", "text")) {
                cases.add(new Object[] {profile[0], profile[1], profile[2], profile[3], format});
            }
        }
        return cases;
    }

    @Test(timeout = 60000)
    public void browserSdkPreservesUnicodeUnaryMessage() throws Exception {
        // 页面先创建独立 origin，真实浏览器保留 CORS 和本地网络检查。
        try (GrpcWebBrowserProcess browser = GrpcWebBrowserProcess.startVersion(this.profile)) {
            String origin = browser.awaitOrigin();
            MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
            configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                    .protocol("grpc-web").contextPath("/web").option("streamBody", true) //
                    .option("corsOrigins", origin) //
                    .option("corsHeaders", "content-type,x-grpc-web,x-user-agent,custom-header,grpc-timeout");
            try (MetContext provider = configuration.buildContext()) {
                // Meteor 公开发布同一契约，SDK 的默认媒体类型和帧处理保持原样。
                provider.start();
                GrpcProtoResource proto = new GrpcProtoResource(getClass().getResource("/protocol/proto/grpc/web.proto"));
                provider.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                        .aliasName("grpc-web", "web.Echo").protocol("grpc-web") //
                        .serialize("Protobuf").bindResource("grpc-web", proto).register();

                // 当前版本官方客户端在浏览器内发送请求并解释业务响应。
                int port = provider.getListenAddress("grpc-web").getPort();
                String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/web";
                JsonNode result = browser.invoke(target, this.format);
                assertFalse("Browser SDK returned an error: " + result, result.has("error"));
                assertEquals("中文🙂 <&>", result.required("value").textValue());
                assertEquals(7, result.required("count").intValue());
            }
        }
    }
}
