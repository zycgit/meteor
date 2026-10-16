/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectDemoServiceImpl;
import net.hasor.example.protocol.connect.ConnectMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.connect.ConnectMetadata;
import net.hasor.meteor.protocol.connect.ConnectStatusException;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** 与官方 Connect Node 双向互通，Meteor 一侧只经 bootstrap 发布和订阅服务。 */
@RunWith(Parameterized.class)
public class ConnectOfficialInteropTest {
    private static final String SERVICE = "meteor.connect.demo.ConnectDemoService";
    private final        String serialization;
    private final        String format;

    public ConnectOfficialInteropTest(String serialization, String format) {
        this.serialization = serialization;
        this.format = format;
    }

    @Parameterized.Parameters(name = "encoding={0}")
    public static Object[][] formats() {
        return new Object[][] { { "Protobuf", "proto" }, { "Json", "json" } };
    }

    @Test(timeout = 30000)
    public void officialClientCallsMeteorUnaryHttp1() throws Exception {
        // 配置 HTTP/1 和 Connect 挂载，双方使用同一份公开 proto 定义。
        MetConfiguration configuration = this.configuration(true, 1);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，不直接创建协议会话或调度器。
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 由官方 Connect Node 客户端完成单次独立调用。
            String[] peerArguments = { //
                    "client", target, //
                    "--http-version=1.1", "--format=" + this.format, //
                    "--scenario=echo" //
            };
            try (ConnectNodeProcess peer = ConnectNodeProcess.start(peerArguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Connect 中文 🙂", result.path("result").path("text").asText());
                assertEquals(42, result.path("result").path("number").asInt());
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientCallsMeteorUnaryHttp2() throws Exception {
        // 配置 HTTP/2 和 Connect 挂载，双方使用同一份公开 proto 定义。
        MetConfiguration configuration = this.configuration(true, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，不直接创建协议会话或调度器。
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 由官方 Connect Node 客户端完成单次独立调用。
            String[] peerArguments = { //
                    "client", target, //
                    "--http-version=2", "--format=" + this.format, //
                    "--scenario=echo" //
            };
            try (ConnectNodeProcess peer = ConnectNodeProcess.start(peerArguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Connect 中文 🙂", result.path("result").path("text").asText());
                assertEquals(42, result.path("result").path("number").asInt());
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientCallsMeteorServerStreamHttp1() throws Exception {
        // 配置 HTTP/1 和 Connect 挂载，双方使用同一份公开 proto 定义。
        MetConfiguration configuration = this.configuration(true, 1);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，不直接创建协议会话或调度器。
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 由官方 Connect Node 客户端完成单次独立调用。
            String[] peerArguments = { //
                    "client", target, //
                    "--http-version=1.1", "--format=" + this.format, //
                    "--scenario=count" //
            };
            try (ConnectNodeProcess peer = ConnectNodeProcess.start(peerArguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals(3, result.path("result").size());
                assertEquals(0, result.path("result").get(0).path("number").asInt());
                assertEquals(2, result.path("result").get(2).path("number").asInt());
                assertEquals("Connect 中文 🙂", result.path("result").get(2).path("text").asText());
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientCallsMeteorServerStreamHttp2() throws Exception {
        // 配置 HTTP/2 和 Connect 挂载，双方使用同一份公开 proto 定义。
        MetConfiguration configuration = this.configuration(true, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，不直接创建协议会话或调度器。
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 由官方 Connect Node 客户端完成单次独立调用。
            String[] peerArguments = { //
                    "client", target, //
                    "--http-version=2", "--format=" + this.format, //
                    "--scenario=count" //
            };
            try (ConnectNodeProcess peer = ConnectNodeProcess.start(peerArguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals(3, result.path("result").size());
                assertEquals(0, result.path("result").get(0).path("number").asInt());
                assertEquals(2, result.path("result").get(2).path("number").asInt());
                assertEquals("Connect 中文 🙂", result.path("result").get(2).path("text").asText());
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientCallsMeteorClientStream() throws Exception {
        // 配置 HTTP/2 和 Connect 挂载，双方使用同一份公开 proto 定义。
        MetConfiguration configuration = this.configuration(true, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，不直接创建协议会话或调度器。
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 由官方 Connect Node 客户端完成单次独立调用。
            String[] peerArguments = { //
                    "client", target, //
                    "--http-version=2", "--format=" + this.format, //
                    "--scenario=collect" //
            };
            try (ConnectNodeProcess peer = ConnectNodeProcess.start(peerArguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("sum", result.path("result").path("text").asText());
                assertEquals(10, result.path("result").path("number").asInt());
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientCallsMeteorBidiStream() throws Exception {
        // 配置 HTTP/2 和 Connect 挂载，双方使用同一份公开 proto 定义。
        MetConfiguration configuration = this.configuration(true, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，不直接创建协议会话或调度器。
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 由官方 Connect Node 客户端完成单次独立调用。
            String[] peerArguments = { //
                    "client", target, //
                    "--http-version=2", "--format=" + this.format, //
                    "--scenario=chat" //
            };
            try (ConnectNodeProcess peer = ConnectNodeProcess.start(peerArguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals(3, result.path("result").size());
                assertEquals("中文", result.path("result").get(1).path("text").asText());
                assertEquals(3, result.path("result").get(1).path("number").asInt());
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientCallsMeteorSafeGet() throws Exception {
        // 配置 HTTP/1 和 Connect 挂载，双方使用同一份公开 proto 定义。
        MetConfiguration configuration = this.configuration(true, 1);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，不直接创建协议会话或调度器。
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 由官方 Connect Node 客户端完成单次独立调用。
            String[] peerArguments = { //
                    "client", target, //
                    "--http-version=1.1", "--format=" + this.format, //
                    "--scenario=safeEcho", "--get=true" //
            };
            try (ConnectNodeProcess peer = ConnectNodeProcess.start(peerArguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Connect GET 中文 🙂", result.path("result").path("text").asText());
                assertEquals(42, result.path("result").path("number").asInt());
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientCallsMeteorCompressedUnary() throws Exception {
        // 配置 HTTP/1 和 Connect 挂载，双方使用同一份公开 proto 定义。
        MetConfiguration configuration = this.configuration(true, 1, true, false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，不直接创建协议会话或调度器。
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 由官方 Connect Node 客户端完成单次独立调用。
            String[] peerArguments = { //
                    "client", target, //
                    "--http-version=1.1", "--format=" + this.format, //
                    "--scenario=echo", "--gzip=true" //
            };
            try (ConnectNodeProcess peer = ConnectNodeProcess.start(peerArguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Connect 中文 🙂", result.path("result").path("text").asText());
                assertEquals(42, result.path("result").path("number").asInt());
                assertEquals("gzip", result.path("header").path("content-encoding").asText());
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientCallsMeteorCompressedServerStream() throws Exception {
        // 配置 HTTP/2 和 Connect 挂载，双方使用同一份公开 proto 定义。
        MetConfiguration configuration = this.configuration(true, 2, true, false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，不直接创建协议会话或调度器。
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 由官方 Connect Node 客户端完成单次独立调用。
            String[] peerArguments = { //
                    "client", target, //
                    "--http-version=2", "--format=" + this.format, //
                    "--scenario=count", "--gzip=true" //
            };
            try (ConnectNodeProcess peer = ConnectNodeProcess.start(peerArguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals(3, result.path("result").size());
                assertEquals(0, result.path("result").get(0).path("number").asInt());
                assertEquals(2, result.path("result").get(2).path("number").asInt());
                assertEquals("Connect 中文 🙂", result.path("result").get(2).path("text").asText());
                assertEquals("gzip", result.path("header").path("connect-content-encoding").asText());
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsOfficialUnaryHttp1() throws Exception {
        // 独立官方服务监听 HTTP/1，Meteor 只配置出站 Connect 通道。
        MetConfiguration configuration = this.configuration(false, 1);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=1.1"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，并连接到独立官方服务的真实监听端口。
            client.start();
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");
            ConnectDemoService remote = client.getRsfClient(target).getRemote(info);

            // 验证服务返回的独立编码结果与流式消息顺序。
            ConnectMessage result = remote.echo(ConnectMessage.of("Meteor 中文 🙂", 41));
            assertEquals("Meteor 中文 🙂", result.getText());
            assertEquals(42, result.getNumber());
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsOfficialUnaryHttp2() throws Exception {
        // 独立官方服务监听 HTTP/2，Meteor 只配置出站 Connect 通道。
        MetConfiguration configuration = this.configuration(false, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=2"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，并连接到独立官方服务的真实监听端口。
            client.start();
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");
            ConnectDemoService remote = client.getRsfClient(target).getRemote(info);

            // 验证服务返回的独立编码结果与流式消息顺序。
            ConnectMessage result = remote.echo(ConnectMessage.of("Meteor 中文 🙂", 41));
            assertEquals("Meteor 中文 🙂", result.getText());
            assertEquals(42, result.getNumber());
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsOfficialServerStreamHttp1() throws Exception {
        // 独立官方服务监听 HTTP/1，Meteor 只配置出站 Connect 通道。
        MetConfiguration configuration = this.configuration(false, 1);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=1.1"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，并连接到独立官方服务的真实监听端口。
            client.start();
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");
            ConnectDemoService remote = client.getRsfClient(target).getRemote(info);

            // 验证服务返回的独立编码结果与流式消息顺序。
            List<ConnectMessage> received = new ArrayList<>();
            MetCaller.consume(remote.count(ConnectMessage.of("Meteor 中文 🙂", 3)), received::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(3, received.size());
            assertEquals(0, received.get(0).getNumber());
            assertEquals(2, received.get(2).getNumber());
            assertEquals("Meteor 中文 🙂", received.get(2).getText());
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsOfficialServerStreamHttp2() throws Exception {
        // 独立官方服务监听 HTTP/2，Meteor 只配置出站 Connect 通道。
        MetConfiguration configuration = this.configuration(false, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=2"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，并连接到独立官方服务的真实监听端口。
            client.start();
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");
            ConnectDemoService remote = client.getRsfClient(target).getRemote(info);

            // 验证服务返回的独立编码结果与流式消息顺序。
            List<ConnectMessage> received = new ArrayList<>();
            MetCaller.consume(remote.count(ConnectMessage.of("Meteor 中文 🙂", 3)), received::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(3, received.size());
            assertEquals(0, received.get(0).getNumber());
            assertEquals(2, received.get(2).getNumber());
            assertEquals("Meteor 中文 🙂", received.get(2).getText());
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsOfficialClientStream() throws Exception {
        // 独立官方服务监听 HTTP/2，Meteor 只配置出站 Connect 通道。
        MetConfiguration configuration = this.configuration(false, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=2"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，并连接到独立官方服务的真实监听端口。
            client.start();
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");
            ConnectDemoService remote = client.getRsfClient(target).getRemote(info);

            // 验证服务返回的独立编码结果与流式消息顺序。
            List<ConnectMessage> requests = List.of(ConnectMessage.of("first", 2), //
                    ConnectMessage.of("中文", 3), ConnectMessage.of("last", 5));
            ConnectMessage result = remote.collect(MetCaller.fromIterable(requests));
            assertEquals("sum", result.getText());
            assertEquals(10, result.getNumber());
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsOfficialBidiStream() throws Exception {
        // 独立官方服务监听 HTTP/2，Meteor 只配置出站 Connect 通道。
        MetConfiguration configuration = this.configuration(false, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=2"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，并连接到独立官方服务的真实监听端口。
            client.start();
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");
            ConnectDemoService remote = client.getRsfClient(target).getRemote(info);

            // 验证服务返回的独立编码结果与流式消息顺序。
            List<ConnectMessage> requests = List.of(ConnectMessage.of("first", 2), //
                    ConnectMessage.of("中文", 3), ConnectMessage.of("last", 5));
            List<ConnectMessage> received = new ArrayList<>();
            MetCaller.consume(remote.chat(MetCaller.fromIterable(requests)), received::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(3, received.size());
            assertEquals("中文", received.get(1).getText());
            assertEquals(3, received.get(1).getNumber());
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsOfficialSafeGet() throws Exception {
        // 独立官方服务监听 HTTP/1，Meteor 只配置出站 Connect 通道。
        MetConfiguration configuration = this.configuration(false, 1, false, true);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=1.1"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，并连接到独立官方服务的真实监听端口。
            client.start();
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");

            // 验证服务返回的独立编码结果与流式消息顺序。
            Class<?>[] types = { ConnectMessage.class };
            Object[] arguments = { ConnectMessage.of("Meteor 中文 🙂", 41) };
            MetResponse response = client.getRsfClient(target).asyncInvoke(info, "safeEcho", types, arguments) //
                    .get(5, TimeUnit.SECONDS);
            ConnectMessage result = (ConnectMessage) response.getData();
            assertEquals("Meteor 中文 🙂", result.getText());
            assertEquals(42, result.getNumber());
            ConnectMetadata metadata = ConnectMetadata.from(response);
            assertEquals(List.of("GET"), metadata.getHeader("x-connect-test-http-method"));
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsOfficialCompressedUnary() throws Exception {
        // 独立官方服务监听 HTTP/1，Meteor 只配置出站 Connect 通道。
        MetConfiguration configuration = this.configuration(false, 1, true, false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=1.1"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，并连接到独立官方服务的真实监听端口。
            client.start();
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");
            ConnectDemoService remote = client.getRsfClient(target).getRemote(info);

            // 验证服务返回的独立编码结果与流式消息顺序。
            ConnectMessage result = remote.echo(ConnectMessage.of("Meteor 中文 🙂", 41));
            assertEquals("Meteor 中文 🙂", result.getText());
            assertEquals(42, result.getNumber());
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsOfficialCompressedServerStream() throws Exception {
        // 独立官方服务监听 HTTP/2，Meteor 只配置出站 Connect 通道。
        MetConfiguration configuration = this.configuration(false, 2, true, false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=2"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，并连接到独立官方服务的真实监听端口。
            client.start();
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");
            ConnectDemoService remote = client.getRsfClient(target).getRemote(info);

            // 验证服务返回的独立编码结果与流式消息顺序。
            List<ConnectMessage> received = new ArrayList<>();
            MetCaller.consume(remote.count(ConnectMessage.of("Meteor 中文 🙂", 3)), received::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(3, received.size());
            assertEquals(0, received.get(0).getNumber());
            assertEquals(2, received.get(2).getNumber());
            assertEquals("Meteor 中文 🙂", received.get(2).getText());
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesMeteorMetadata() throws Exception {
        // 启动 HTTP/1 服务，在公开过滤器中回送单次调用的文本与二进制元数据。
        MetConfiguration configuration = this.configuration(true, 1);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().bindFilter("connect-interop-metadata", (request, response, chain) -> {
                ConnectMetadata input = ConnectMetadata.from(request);
                ConnectMetadata output = ConnectMetadata.from(response);
                output.addHeader("x-connect-test-request", input.getHeader("x-connect-test-request").get(0));
                output.addHeader("x-connect-test-repeat", "first");
                output.addHeader("x-connect-test-repeat", "second");
                output.addTrailer("x-connect-test-trailer", "finished");
                byte[] binary = input.getBinaryHeader("x-connect-test-request-bin").get(0);
                output.addBinaryTrailer("x-connect-test-trailer-bin", binary);
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 官方客户端分别观察初始字段和结束字段，不依赖 Meteor 的元数据解码实现。
            try (ConnectNodeProcess peer = ConnectNodeProcess.start("client", target, "--http-version=1.1", //
                    "--format=" + this.format, "--scenario=metadata")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("request-value", result.path("header").path("x-connect-test-request").asText());
                assertEquals("first, second", result.path("header").path("x-connect-test-repeat").asText());
                assertEquals("finished", result.path("trailer").path("x-connect-test-trailer").asText());
                String binary = result.path("trailer").path("x-connect-test-trailer-bin").asText();
                assertArrayEquals(new byte[] { 0, 1, -1 }, Base64.getDecoder().decode(binary));
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesOfficialMetadata() throws Exception {
        // 官方 Node 服务维护自己的元数据，Meteor 只通过业务接口和异步调用入口访问。
        MetConfiguration configuration = this.configuration(false, 1);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=1.1"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            client.publisher().bindFilter("connect-interop-request-metadata", (request, response, chain) -> {
                ConnectMetadata.from(request).addHeader("x-connect-test-request", "request-value");
                chain.doFilter(request, response);
            });
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");

            // 等待真实响应后从该响应读取初始和结束元数据。
            Class<?>[] types = { ConnectMessage.class };
            Object[] arguments = { ConnectMessage.of("metadata", 1) };
            MetResponse response = client.getRsfClient(target).asyncInvoke(info, "echo", types, arguments) //
                    .get(5, TimeUnit.SECONDS);
            ConnectMetadata metadata = ConnectMetadata.from(response);
            assertEquals(List.of("request-value"), metadata.getHeader("x-connect-test-request"));
            String repeated = String.join(", ", metadata.getHeader("x-connect-test-repeat"));
            assertEquals("first, second", repeated);
            assertEquals(List.of("finished"), metadata.getTrailer("x-connect-test-trailer"));
            assertArrayEquals(new byte[] { 0, 1, -1 }, metadata.getBinaryTrailer("x-connect-test-trailer-bin").get(0));
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesMeteorStreamTrailers() throws Exception {
        // 流式结束字段通过 Connect 结束帧传递，与 HTTP/2 原生 trailer 分开验证。
        MetConfiguration configuration = this.configuration(true, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().bindFilter("connect-interop-stream-metadata", (request, response, chain) -> {
                ConnectMetadata output = ConnectMetadata.from(response);
                output.addHeader("x-connect-test-request", "request-value");
                output.addTrailer("x-connect-test-trailer", "finished");
                output.addBinaryTrailer("x-connect-test-trailer-bin", new byte[] { 0, 1, -1 });
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 官方客户端消费响应流至结束后检查 Connect 元数据。
            try (ConnectNodeProcess peer = ConnectNodeProcess.start("client", target, "--http-version=2", //
                    "--format=" + this.format, "--scenario=count")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals(3, result.path("result").size());
                assertEquals("request-value", result.path("header").path("x-connect-test-request").asText());
                assertEquals("finished", result.path("trailer").path("x-connect-test-trailer").asText());
                String binary = result.path("trailer").path("x-connect-test-trailer-bin").asText();
                assertArrayEquals(new byte[] { 0, 1, -1 }, Base64.getDecoder().decode(binary));
            }
        }
    }

    @Test(timeout = 30000)
    @SuppressWarnings("unchecked")
    public void meteorClientReceivesOfficialStreamTrailers() throws Exception {
        // 从正式异步入口取得流和响应元数据视图，保持用户可见的真实生命周期。
        MetConfiguration configuration = this.configuration(false, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=2"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");
            Class<?>[] types = { ConnectMessage.class };
            Object[] arguments = { ConnectMessage.of("metadata", 3) };
            MetResponse response = client.getRsfClient(target).asyncInvoke(info, "count", types, arguments) //
                    .get(5, TimeUnit.SECONDS);

            // 消费到结束后读取官方结束帧中的文本和二进制尾部元数据。
            List<ConnectMessage> received = new ArrayList<>();
            Flow.Publisher<ConnectMessage> messages = (Flow.Publisher<ConnectMessage>) response.getData();
            MetCaller.consume(messages, received::add).get(5, TimeUnit.SECONDS);
            ConnectMetadata metadata = ConnectMetadata.from(response);
            assertEquals(3, received.size());
            assertEquals(List.of("finished"), metadata.getTrailer("x-connect-test-trailer"));
            assertArrayEquals(new byte[] { 0, 1, -1 }, metadata.getBinaryTrailer("x-connect-test-trailer-bin").get(0));
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesMeteorErrorDetails() throws Exception {
        // 服务可显式抛出 Connect 错误，详情依照 proto 编为 Any 值。
        MetConfiguration configuration = this.configuration(true, 1);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        Descriptor reply = resource.method(SERVICE, "fail").getOutputType();
        DynamicMessage detail = DynamicMessage.newBuilder(reply) //
                .setField(reply.findFieldByName("text"), "failure detail 中文") //
                .setField(reply.findFieldByName("number"), 9).build();
        String encoded = Base64.getEncoder().encodeToString(detail.toByteArray());
        Map<String, Object> errorDetail = Map.of("type", reply.getFullName(), "value", encoded);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().bindFilter("connect-interop-error-details", (request, response, chain) -> {
                throw new ConnectStatusException("invalid_argument", "connect failure 中文", List.of(errorDetail), null);
            });
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 官方客户端按自己的错误解析和 Any 消息描述恢复详情。
            try (ConnectNodeProcess peer = ConnectNodeProcess.start("client", target, "--http-version=1.1", //
                    "--format=" + this.format, "--scenario=fail")) {
                JsonNode result = peer.awaitResult();
                assertEquals(3, result.path("error").path("code").asInt());
                assertEquals("connect failure 中文", result.path("error").path("message").asText());
                JsonNode returned = result.path("error").path("details").get(0);
                assertEquals("failure detail 中文", returned.path("text").asText());
                assertEquals(9, returned.path("number").asInt());
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesOfficialErrorDetails() throws Exception {
        // 官方服务独立生成 InvalidArgument 与 Any 详情，Meteor 使用正式订阅入口。
        MetConfiguration configuration = this.configuration(false, 1);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=1.1"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");
            Class<?>[] types = { ConnectMessage.class };
            Object[] arguments = { ConnectMessage.of("failure", 0) };

            // 异步 Future 保留标准错误和详情，避免同步代理包装影响异常断言。
            MetFuture result = client.getRsfClient(target).asyncInvoke(info, "fail", types, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            ConnectStatusException status = ConnectStatusException.find(failure);
            assertNotNull(status);
            assertEquals("invalid_argument", status.getCode());
            assertEquals("connect failure 中文", status.getDescription());
            assertEquals(1, status.getDetails().size());
            Map<String, Object> returned = status.getDetails().get(0);
            assertEquals("meteor.connect.demo.EchoReply", returned.get("type"));
            Descriptor reply = resource.method(SERVICE, "fail").getOutputType();
            byte[] encoded = Base64.getDecoder().decode((String) returned.get("value"));
            DynamicMessage detail = DynamicMessage.parseFrom(reply, encoded);
            assertEquals("failure detail 中文", detail.getField(reply.findFieldByName("text")));
            assertEquals(9, detail.getField(reply.findFieldByName("number")));
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesMeteorStreamFailure() throws Exception {
        // 消息源在流中失败，协议需以结束帧传回错误而非正常 EOF。
        MetConfiguration configuration = this.configuration(true, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            int port = server.getListenAddress("connect").getPort();
            String target = "--url=http://127.0.0.1:" + port + "/connect";

            // 官方客户端识别流式失败，保留中文原因而不误报普通空流。
            String[] peerArguments = { //
                    "client", target, //
                    "--http-version=2", "--format=" + this.format, //
                    "--scenario=broken" //
            };
            try (ConnectNodeProcess peer = ConnectNodeProcess.start(peerArguments)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.toString(), result.has("error"));
                assertEquals(2, result.path("error").path("code").asInt());
                assertEquals("connect source failure 中文", result.path("error").path("message").asText());
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesOfficialStreamFailureAfterData() throws Exception {
        // 官方服务先独立发送一条数据，再发送 ResourceExhausted 错误结束帧。
        MetConfiguration configuration = this.configuration(false, 2);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        try (ConnectNodeProcess peer = ConnectNodeProcess.start("server", "--http-version=2"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<ConnectDemoService> info = client.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", SERVICE).protocol("connect").serialize(this.serialization) //
                    .bindResource(resource).register();
            InterAddress target = new InterAddress("connect", "127.0.0.1", port, "default");
            ConnectDemoService remote = client.getRsfClient(target).getRemote(info);

            // 已接收数据仍然有效，随后 Future 必须保留标准流式错误与详情。
            List<ConnectMessage> received = new ArrayList<>();
            var finished = MetCaller.consume(remote.broken(ConnectMessage.of("first", 1)), received::add);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                finished.get(5, TimeUnit.SECONDS);
            });
            assertEquals(1, received.size());
            assertEquals("first", received.get(0).getText());
            ConnectStatusException status = ConnectStatusException.find(failure);
            assertNotNull(status);
            assertEquals("resource_exhausted", status.getCode());
            assertEquals("stream failure 中文", status.getDescription());
            assertEquals(1, status.getDetails().size());
        }
    }

    private MetConfiguration configuration(boolean listening, int version) {
        return this.configuration(listening, version, false, false);
    }

    private MetConfiguration configuration(boolean listening, int version, boolean gzip, boolean get) {
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        var connector = configuration.connector("http").option("httpVersion", version);
        if (listening) {
            connector.bind("127.0.0.1", 0);
        }
        var protocol = connector.protocol("connect").contextPath("/connect").option("streamBody", true);
        if (gzip) {
            protocol.option("compression", "gzip");
        }
        if (get) {
            protocol.option("get", true);
        }
        return configuration;
    }
}
