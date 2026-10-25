/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.xmlrpc;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoService;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.support.JavaSdkPeerUtils;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import static org.junit.Assert.*;

/** Apache 官方 Java SDK 使用标准 XML-RPC 调用 Meteor 提供者，不启用厂商扩展。 */
public class ApacheXmlRpcProviderTest {
    private static final String PEER_CLASS = "net.hasor.peer.protocol.xmlrpc.ApacheXmlRpcPeer";

    @Test(timeout = 30000)
    public void javaSdkCallsPublishedServiceWithUnicodeText() throws Exception {
        // 发布普通业务接口，SDK 只使用 Meteor 实际绑定的网络地址。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").protocol("xmlrpc").register();

            // Apache 3.1.3 将补充平面字符拆成非法引用，本成功场景使用 BMP 中文和 XML 转义。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            String expected = "Java SDK 中文 <&>";
            Map<String, Object> request = this.request(target, "demo.echo", new Object[] { expected });
            Map<String, Object> response = JavaSdkPeerUtils.exchange("xmlrpc", PEER_CLASS, request);

            // 读取 SDK 的原生结果，Meteor 或手写 XML 解析器不参与客户端解码。
            assertFalse(response.toString(), response.containsKey("errorCode"));
            assertEquals(expected, response.get("result"));
        }
    }

    @Test(timeout = 30000)
    public void apache313InvalidSurrogateReferencesAreRejectedByMeteor() throws Exception {
        // XML 1.0 支持 emoji；本例固定验证旧 SDK 将 UTF-16 代理对拆成非法数字引用的边界。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").protocol("xmlrpc").register();

            // SDK 对此字符产生 &#55357;&#56898;，不能由服务端放宽 XML 规则来容忍。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Map<String, Object> request = this.request(target, "demo.echo", new Object[] { "🙂" });
            Map<String, Object> response = JavaSdkPeerUtils.exchange("xmlrpc", PEER_CLASS, request);

            // decodeRequest 的解析失败契约为 -32700；超时、进程失败或任意 SDK 异常不算通过。
            assertFalse(response.containsKey("result"));
            assertEquals(-32700, response.get("errorCode"));
            assertEquals("Parse error", response.get("errorMessage"));
        }
    }

    @Test(timeout = 30000)
    public void javaSdkPreservesPositionalIntegerArguments() throws Exception {
        // 减法可以观察参数顺序，不能用交换律掩盖位置绑定错误。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").protocol("xmlrpc").register();

            // execute 接收标准 Object[]，在独立 SDK JVM 内产生 XML-RPC 报文。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Map<String, Object> request = this.request(target, "demo.subtract", new Object[] { 9, 4 });
            Map<String, Object> response = JavaSdkPeerUtils.exchange("xmlrpc", PEER_CLASS, request);

            // Apache 返回 Java Integer；位置倒置会得到不同结果。
            assertFalse(response.containsKey("errorCode"));
            assertEquals(5, response.get("result"));
        }
    }

    @Test(timeout = 30000)
    public void javaSdkPreservesBinaryValues() throws Exception {
        // 发布二进制回显方法，覆盖 XML-RPC 的 base64 标准值类型。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").protocol("xmlrpc").register();

            // 官方 SDK 负责 byte[] 与 base64 的双向映射。
            byte[] expected = new byte[] { 0, 1, 127, (byte) 128, (byte) 255 };
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Map<String, Object> request = this.request(target, "demo.binary", new Object[] { expected });
            Map<String, Object> response = JavaSdkPeerUtils.exchange("xmlrpc", PEER_CLASS, request);

            // 不把二进制转换为字符串，也不丢失无符号高位字节。
            assertFalse(response.containsKey("errorCode"));
            assertArrayEquals(expected, (byte[]) response.get("result"));
        }
    }

    @Test(timeout = 30000)
    @SuppressWarnings("unchecked")
    public void javaSdkPreservesNestedStructValues() throws Exception {
        // 普通 Java Map 表达标准 struct，不使用 Apache 可序列化对象扩展。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").protocol("xmlrpc").register();

            // 嵌套 struct 与数组同属一个业务值，SDK 负责其完整往返。
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("name", "订单 <一>");
            expected.put("buyer", Map.of("active", true, "count", 3));
            expected.put("values", new Object[] { "first", "second" });
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            Map<String, Object> request = this.request(target, "demo.struct", new Object[] { expected });
            Map<String, Object> response = JavaSdkPeerUtils.exchange("xmlrpc", PEER_CLASS, request);

            // 核对 SDK 原生 Map 和 Object[] 结果，避免只验证 HTTP 成功状态。
            assertFalse(response.containsKey("errorCode"));
            Map<String, Object> result = (Map<String, Object>) response.get("result");
            assertEquals(3, result.size());
            assertEquals("订单 <一>", result.get("name"));
            assertEquals(Map.of("active", true, "count", 3), result.get("buyer"));
            assertArrayEquals(new Object[] { "first", "second" }, (Object[]) result.get("values"));
        }
    }

    @Test(timeout = 30000)
    public void javaSdkReadsPublishedBusinessFault() throws Exception {
        // Meteor 业务抛出的错误码应由 Apache 客户端按标准 fault 读取。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo").protocol("xmlrpc").register();

            // 对端使用 execute 接收 XmlRpcException，不读取或拼接原始 XML。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            String expected = "业务拒绝 <&>";
            Map<String, Object> request = this.request(target, "demo.fail", new Object[] { expected });
            Map<String, Object> response = JavaSdkPeerUtils.exchange("xmlrpc", PEER_CLASS, request);

            // 错误码和文本必须来自真实 fault，连接错误或超时不能通过断言。
            assertFalse(response.containsKey("result"));
            assertEquals(42, response.get("errorCode"));
            assertEquals(expected, response.get("errorMessage"));
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("xmlrpc").contextPath("/rpc");
        return configuration;
    }

    private Map<String, Object> request(String endpoint, String method, Object[] parameters) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("endpoint", endpoint);
        request.put("method", method);
        request.put("parameters", parameters);
        return request;
    }
}
