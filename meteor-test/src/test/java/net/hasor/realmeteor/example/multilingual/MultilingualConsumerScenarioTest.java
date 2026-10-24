/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.multilingual;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.example.order.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.example.order.Order;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.cobble.StringUtils;
import org.junit.Test;
import static org.junit.Assert.*;

/** 外部 Python、Node.js 和 curl 消费者与 Java 消费者访问一次发布的订单服务。 */
public class MultilingualConsumerScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test(timeout = 30000)
    public void pythonJsonRpcWritesTheOrderReadByJava() throws Exception {
        // 提供者挂载全部协议，Java 消费者只需要 RSF 出口且不监听端口。
        MetConfiguration incoming = DirectRpcExample.server(ContainerProcess.runnerHost(), 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscription = DirectRpcExample.subscribe(consumer, "rsf");

            // 真实 Python 进程通过 JSON-RPC 提交普通订单对象与二进制附件。
            String origin = "http://" + provider.getListenAddress("jsonrpc").getHostPort();
            JsonNode output = this.runConsumer("python3", "python/order_client.py", "save", //
                    "--protocol", "jsonrpc", "--url", origin, "--id", "1101", //
                    "--description", "Python JSON-RPC", "--attachment-text", "python-jsonrpc");
            assertEquals("Python JSON-RPC", output.path("result").path("description").asText());

            // Java 通过另一个协议读取该 ID，证明服务实现和业务存储没有按协议复制。
            OrderDemoService remote = consumer.getRsfClient(provider.getListenAddress("rsf")).getRemote(subscription);
            OrderQuery query = new OrderQuery();
            query.setId(1101);
            Order actual = remote.find(query);
            assertEquals(1101, actual.getId());
            assertEquals("Python JSON-RPC", actual.getDescription());
            assertArrayEquals("python-jsonrpc".getBytes(StandardCharsets.UTF_8), actual.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void pythonXmlRpcWritesTheOrderReadByJava() throws Exception {
        // 两端先启动，再一次发布提供者和注册 Java 消费者。
        MetConfiguration incoming = DirectRpcExample.server(ContainerProcess.runnerHost(), 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscription = DirectRpcExample.subscribe(consumer, "rsf");

            // Python 标准库将 POJO 映射为 struct，将 byte[] 映射为 base64。
            String origin = "http://" + provider.getListenAddress("xmlrpc").getHostPort();
            JsonNode output = this.runConsumer("python3", "python/order_client.py", "save", //
                    "--protocol", "xmlrpc", "--url", origin, "--id", "1102", //
                    "--description", "Python XML-RPC", "--attachment-text", "python-xmlrpc");
            assertEquals("Python XML-RPC", output.path("result").path("description").asText());

            // RSF 消费者读取来自 XML-RPC 的同一订单，附件保持原始字节。
            OrderDemoService remote = consumer.getRsfClient(provider.getListenAddress("rsf")).getRemote(subscription);
            OrderQuery query = new OrderQuery();
            query.setId(1102);
            Order actual = remote.find(query);
            assertEquals(1102, actual.getId());
            assertEquals("Python XML-RPC", actual.getDescription());
            assertArrayEquals("python-xmlrpc".getBytes(StandardCharsets.UTF_8), actual.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void pythonSoapWritesTheOrderReadByJava() throws Exception {
        // SOAP 的契约由插件从同一个 Java 业务接口生成，例子没有绑定手写 WSDL。
        MetConfiguration incoming = DirectRpcExample.server(ContainerProcess.runnerHost(), 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscription = DirectRpcExample.subscribe(consumer, "rsf");

            // Python 只用标准 XML 与 HTTP 库构造 document/literal 请求。
            String origin = "http://" + provider.getListenAddress("soap").getHostPort();
            JsonNode output = this.runConsumer("python3", "python/order_client.py", "save", //
                    "--protocol", "soap", "--url", origin, "--id", "1103", //
                    "--description", "Python SOAP", "--attachment-text", "python-soap");
            assertEquals("Python SOAP", output.path("result").path("description").asText());

            // Java 接口代理按普通对象读取 SOAP 写入的数据。
            OrderDemoService remote = consumer.getRsfClient(provider.getListenAddress("rsf")).getRemote(subscription);
            OrderQuery query = new OrderQuery();
            query.setId(1103);
            Order actual = remote.find(query);
            assertEquals(1103, actual.getId());
            assertEquals("Python SOAP", actual.getDescription());
            assertArrayEquals("python-soap".getBytes(StandardCharsets.UTF_8), actual.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void nodeJsonRpcWritesTheOrderReadByJava() throws Exception {
        // Node 消费者无需 Meteor SDK，提供者与 Java 消费者仍从 bootstrap 启动。
        MetConfiguration incoming = DirectRpcExample.server(ContainerProcess.runnerHost(), 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscription = DirectRpcExample.subscribe(consumer, "rsf");

            // 原生 fetch 向 JSON-RPC 挂载点发起调用，不加载任何 npm 依赖。
            String origin = "http://" + provider.getListenAddress("jsonrpc").getHostPort();
            JsonNode output = this.runConsumer("node", "node/order-client.mjs", "save", //
                    "--protocol", "jsonrpc", "--url", origin, "--id", "1104", //
                    "--description", "Node JSON-RPC", "--attachment-text", "node-jsonrpc");
            assertEquals("Node JSON-RPC", output.path("result").path("description").asText());

            // Java 使用独立的 RSF 网络连接读取同一业务状态。
            OrderDemoService remote = consumer.getRsfClient(provider.getListenAddress("rsf")).getRemote(subscription);
            OrderQuery query = new OrderQuery();
            query.setId(1104);
            Order actual = remote.find(query);
            assertEquals(1104, actual.getId());
            assertEquals("Node JSON-RPC", actual.getDescription());
            assertArrayEquals("node-jsonrpc".getBytes(StandardCharsets.UTF_8), actual.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void nodeConnectJsonWritesTheOrderReadByJava() throws Exception {
        // Connect 与其他 HTTP 协议共享端口，服务只在提供者容器内注册一次。
        MetConfiguration incoming = DirectRpcExample.server(ContainerProcess.runnerHost(), 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscription = DirectRpcExample.subscribe(consumer, "rsf");

            // 普通 JSON 请求体遵循 proto 字段定义，HTTP 路径包含 proto 服务名和方法名。
            String origin = "http://" + provider.getListenAddress("connect").getHostPort();
            JsonNode output = this.runConsumer("node", "node/order-client.mjs", "save", //
                    "--protocol", "connect", "--url", origin, "--id", "1105", //
                    "--description", "Node Connect", "--attachment-text", "node-connect");
            assertEquals("Node Connect", output.path("result").path("description").asText());

            // 跨协议查询验证 JSON 与二进制 RPC 对相同 Java 对象的解释一致。
            OrderDemoService remote = consumer.getRsfClient(provider.getListenAddress("rsf")).getRemote(subscription);
            OrderQuery query = new OrderQuery();
            query.setId(1105);
            Order actual = remote.find(query);
            assertEquals(1105, actual.getId());
            assertEquals("Node Connect", actual.getDescription());
            assertArrayEquals("node-connect".getBytes(StandardCharsets.UTF_8), actual.getAttachment());
        }
    }

    @Test(timeout = 30000)
    public void curlSavesAndQueriesTheSharedOrder() throws Exception {
        // curl 示例面向可直接复制的 HTTP 调用，提供者通过正式入口启动并发布服务。
        MetConfiguration configuration = DirectRpcExample.server(ContainerProcess.runnerHost(), 0, 0);
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());

            // 运行用户脚本完成保存和查询，不需要 Java、Python 或 Node 客户端 SDK。
            String origin = "http://" + provider.getListenAddress("jsonrpc").getHostPort();
            JsonNode output = this.runConsumer("bash", "curl/order-client.sh", origin, "1106");

            // 脚本最后一行是查询响应，证明两次 HTTP 交换访问同一个订单。
            JsonNode actual = output.path("result");
            assertEquals(1106, actual.path("id").asInt());
            assertEquals("Order from curl", actual.path("description").asText());
            assertEquals("TWV0ZW9y", actual.path("attachment").asText());
        }
    }

    @Test(timeout = 30000)
    public void nodeConnectReadsTheOrderSavedByPythonJsonRpc() throws Exception {
        // 两种语言面对同一次发布，服务端不为任何消费者创建专用业务对象。
        MetConfiguration configuration = DirectRpcExample.server(ContainerProcess.runnerHost(), 0, 0);
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());

            // Python 使用 JSON-RPC 写入，再由 Node 使用 Connect JSON 查询同一个 ID。
            String origin = "http://" + provider.getListenAddress("jsonrpc").getHostPort();
            this.runConsumer("python3", "python/order_client.py", "save", //
                    "--protocol", "jsonrpc", "--url", origin, "--id", "1107", //
                    "--description", "跨语言共享订单", "--attachment-text", "shared-order");
            JsonNode output = this.runConsumer("node", "node/order-client.mjs", "find", //
                    "--protocol", "connect", "--url", origin, "--id", "1107");

            // 原生消费者之间无需经过 Java 测试代码转存数据。
            JsonNode actual = output.path("result");
            assertEquals(1107, actual.path("id").asInt());
            assertEquals("跨语言共享订单", actual.path("description").asText());
            assertEquals("c2hhcmVkLW9yZGVy", actual.path("attachment").asText());
        }
    }

    @Test(timeout = 30000)
    public void pythonJsonRpcReadsTheOrderSavedByJava() throws Exception {
        // 所有容器先启动，再发布同一个订单实现和注册仅支持出站的 Java 消费者。
        MetConfiguration incoming = DirectRpcExample.server(ContainerProcess.runnerHost(), 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscription = DirectRpcExample.subscribe(consumer, "rsf");

            // Java 通过真实 RSF 网络调用保存订单，附件保留非空二进制内容。
            OrderDemoService remote = consumer.getRsfClient(provider.getListenAddress("rsf")).getRemote(subscription);
            byte[] attachment = "java-jsonrpc".getBytes(StandardCharsets.UTF_8);
            Order order = new Order();
            order.setId(1111);
            order.setDescription("Java to Python JSON-RPC");
            order.setAttachment(attachment);
            remote.save(order);

            // Python 脚本只发送查询条件，不用客户端默认值覆盖已有订单字段。
            String origin = "http://" + provider.getListenAddress("jsonrpc").getHostPort();
            JsonNode output = this.runConsumer("python3", "python/order_client.py", "find", //
                    "--protocol", "jsonrpc", "--url", origin, "--id", "1111");
            JsonNode actual = output.path("result");
            assertEquals(1111, actual.path("id").asInt());
            assertEquals("Java to Python JSON-RPC", actual.path("description").asText());
            String expectedAttachment = Base64.getEncoder().encodeToString(attachment);
            assertEquals(expectedAttachment, actual.path("attachment").asText());
        }
    }

    @Test(timeout = 30000)
    public void pythonXmlRpcReadsTheOrderSavedByJava() throws Exception {
        // 发布及订阅复用实际用户示例，Python 查询不绕过插件的 XML-RPC 解析。
        MetConfiguration incoming = DirectRpcExample.server(ContainerProcess.runnerHost(), 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscription = DirectRpcExample.subscribe(consumer, "rsf");

            // Java 消费者使用接口代理保存订单，业务状态只存在于提供者内部。
            OrderDemoService remote = consumer.getRsfClient(provider.getListenAddress("rsf")).getRemote(subscription);
            byte[] attachment = "java-xmlrpc".getBytes(StandardCharsets.UTF_8);
            Order order = new Order();
            order.setId(1112);
            order.setDescription("Java to Python XML-RPC");
            order.setAttachment(attachment);
            remote.save(order);

            // Python 标准库将 XML-RPC 返回的 base64 还原为字节，再按统一 JSON 形态输出。
            String origin = "http://" + provider.getListenAddress("xmlrpc").getHostPort();
            JsonNode output = this.runConsumer("python3", "python/order_client.py", "find", //
                    "--protocol", "xmlrpc", "--url", origin, "--id", "1112");
            JsonNode actual = output.path("result");
            assertEquals(1112, actual.path("id").asInt());
            assertEquals("Java to Python XML-RPC", actual.path("description").asText());
            String expectedAttachment = Base64.getEncoder().encodeToString(attachment);
            assertEquals(expectedAttachment, actual.path("attachment").asText());
        }
    }

    @Test(timeout = 30000)
    public void pythonSoapReadsTheOrderSavedByJava() throws Exception {
        // SOAP 挂载共享同一次业务发布，不为查询消费者注册额外的服务实例。
        MetConfiguration incoming = DirectRpcExample.server(ContainerProcess.runnerHost(), 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscription = DirectRpcExample.subscribe(consumer, "rsf");

            // 写入仍走 RSF，确保 SOAP 消费者读取的是跨协议共享的数据。
            OrderDemoService remote = consumer.getRsfClient(provider.getListenAddress("rsf")).getRemote(subscription);
            byte[] attachment = "java-soap".getBytes(StandardCharsets.UTF_8);
            Order order = new Order();
            order.setId(1113);
            order.setDescription("Java to Python SOAP");
            order.setAttachment(attachment);
            remote.save(order);

            // 查询请求仅有 arg0.id，返回的描述和附件来自服务端原有订单。
            String origin = "http://" + provider.getListenAddress("soap").getHostPort();
            JsonNode output = this.runConsumer("python3", "python/order_client.py", "find", //
                    "--protocol", "soap", "--url", origin, "--id", "1113");
            JsonNode actual = output.path("result");
            assertEquals(1113, actual.path("id").asInt());
            assertEquals("Java to Python SOAP", actual.path("description").asText());
            String expectedAttachment = Base64.getEncoder().encodeToString(attachment);
            assertEquals(expectedAttachment, actual.path("attachment").asText());
        }
    }

    @Test(timeout = 30000)
    public void nodeJsonRpcReadsTheOrderSavedByJava() throws Exception {
        // Node 使用用户脚本的 find 分支，提供者和 Java 消费者仍从 bootstrap 构建。
        MetConfiguration incoming = DirectRpcExample.server(ContainerProcess.runnerHost(), 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscription = DirectRpcExample.subscribe(consumer, "rsf");

            // Java 保存带附件的业务对象，Node 不参与数据准备。
            OrderDemoService remote = consumer.getRsfClient(provider.getListenAddress("rsf")).getRemote(subscription);
            byte[] attachment = "java-node".getBytes(StandardCharsets.UTF_8);
            Order order = new Order();
            order.setId(1114);
            order.setDescription("Java to Node JSON-RPC");
            order.setAttachment(attachment);
            remote.save(order);

            // fetch 查询使用 JSON-RPC 方法 orders.find，结果应完整保留三个字段。
            String origin = "http://" + provider.getListenAddress("jsonrpc").getHostPort();
            JsonNode output = this.runConsumer("node", "node/order-client.mjs", "find", //
                    "--protocol", "jsonrpc", "--url", origin, "--id", "1114");
            JsonNode actual = output.path("result");
            assertEquals(1114, actual.path("id").asInt());
            assertEquals("Java to Node JSON-RPC", actual.path("description").asText());
            String expectedAttachment = Base64.getEncoder().encodeToString(attachment);
            assertEquals(expectedAttachment, actual.path("attachment").asText());
        }
    }

    private JsonNode runConsumer(String executable, String script, String... arguments) throws Exception {
        // 启动的正是主源码中的用户脚本，测试不持有另一套协议编码实现。
        String clientDirectory = System.getProperty("meteor.example.clientsDirectory");
        Path scriptPath = Path.of(clientDirectory).resolve(script).toAbsolutePath();
        List<String> command = new ArrayList<>(List.of(executable, scriptPath.toString()));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile("meteor-example-consumer-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command) //
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            String runtime = StringUtils.equals(executable, "python3") ? "python" : "node";
            process = ContainerProcess.start(runtime, builder);
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertTrue("Consumer timed out: " + command + "\n" + output, finished);
            assertEquals("Consumer failed: " + command + "\n" + output, 0, process.exitValue());

            // curl 输出保存和查询两行响应，其余脚本只输出最终业务结果。
            List<String> lines = output.lines().filter(line -> !line.isBlank()).toList();
            assertFalse("Consumer produced no JSON result: " + command, lines.isEmpty());
            return JSON.readTree(lines.get(lines.size() - 1));
        } finally {
            // 进程由测试创建和回收；超时时不遗留外部消费者。
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(log);
        }
    }
}
