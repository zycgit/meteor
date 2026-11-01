/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoService;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoServiceImpl;
import net.hasor.example.protocol.jsonrpc.JsonRpcInteropService;
import net.hasor.example.protocol.jsonrpc.JsonRpcInteropServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.support.JavaSdkPeerUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 独立 jsonrpc4j Java 客户端调用 Meteor 发布的 JSON-RPC 2.0 服务。 */
@NxnRpc(name = "jsonrpc", version = "2.0")
public class JsonRpc4jProviderTest {
    private static final String PEER_CLASS = "net.hasor.peer.protocol.jsonrpc.JsonRpc4jPeer";

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void javaSdkCallsPublishedServiceWithPositionalArguments() throws Exception {
        // Meteor 作为提供者，通过公开入口挂载 JSON-RPC 并发布普通业务接口。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 原生 Java SDK 在独立 JVM 中生成请求并执行真实 HTTP 调用。
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            String expected = "Java SDK 中文 🙂 <&>";
            Map<String, Object> request = this.request(target, "peer.echo", new Object[] { expected }, String.class);
            Map<String, Object> response = JavaSdkPeerUtils.exchange("jsonrpc", PEER_CLASS, request);

            // SDK 解析后的业务结果应与输入一致，不能把 RPC error 当作成功。
            assertFalse(response.containsKey("errorCode"));
            assertEquals(expected, response.get("result"));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void javaSdkCallsPublishedServiceWithNamedArguments() throws Exception {
        // 使用相同正式发布入口，参数名由真实 Java 接口保留。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // Map 的加入顺序刻意与接口相反，减法确保错误的位置绑定不能碰巧通过。
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("right", 4);
            parameters.put("left", 9);
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Map<String, Object> request = this.request(target, "peer.subtract", parameters, Integer.class);
            Map<String, Object> response = JavaSdkPeerUtils.exchange("jsonrpc", PEER_CLASS, request);

            // jsonrpc4j 自行反序列化为要求的 Java Integer。
            assertFalse(response.containsKey("errorCode"));
            assertEquals(5, response.get("result"));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void javaSdkReadsPublishedServiceMethodNotFound() throws Exception {
        // 发布真实服务，仅请求其中不存在的方法，不用模拟 HTTP 响应代替提供者。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // jsonrpc4j 将线上 error 对象解析为自身的 JsonRpcClientException。
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Map<String, Object> request = this.request(target, "peer.missing", new Object[0], String.class);
            Map<String, Object> response = JavaSdkPeerUtils.exchange("jsonrpc", PEER_CLASS, request);

            // 必须是标准方法不存在错误，网络失败或超时不能冒充此结果。
            assertFalse(response.containsKey("result"));
            assertEquals(-32601, response.get("errorCode"));
            assertNotNull(response.get("errorMessage"));
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("jsonrpc").contextPath("/rpc");
        return configuration;
    }

    private Map<String, Object> request(String endpoint, String method, Object parameters, Class<?> resultType) {
        // 此 Map 仅作为本地控制数据；JSON-RPC 请求由外部 SDK 生成。
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("endpoint", endpoint);
        request.put("method", method);
        request.put("parameters", parameters);
        request.put("resultType", resultType);
        return request;
    }
}
