/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.jsonrpc.JsonRpcInteropService;
import net.hasor.example.protocol.jsonrpc.JsonRpcInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.jsonrpc.JsonRpcException;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonRpcInteropTest {

    @Test(timeout = 30000)
    public void jaysonHttpClientPositionalArguments() throws Exception {
        // 通过公开入口配置挂载点，对端只连接实际监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 先启动容器，再发布普通 Java 接口。
            server.start();
            server.publisher().rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 独立 Jayson 客户端验证位置参数、Unicode 正文与字符串请求 ID。
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("client", target, "positional")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void jaysonHttpClientNamedArguments() throws Exception {
        // 通过公开入口配置挂载点，对端只连接实际监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 先启动容器，再发布普通 Java 接口。
            server.start();
            server.publisher().rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 独立 Jayson 客户端验证命名参数与数字请求 ID。
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("client", target, "named")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void jaysonHttpClientNotification() throws Exception {
        // 通过公开入口配置挂载点，对端只连接实际监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 先启动容器，再发布普通 Java 接口。
            server.start();
            server.publisher().rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 独立 Jayson 客户端验证无 ID 通知执行且不产生 RPC 响应。
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("client", target, "notification")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void jaysonHttpClientMixedBatch() throws Exception {
        // 通过公开入口配置挂载点，对端只连接实际监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 先启动容器，再发布普通 Java 接口。
            server.start();
            server.publisher().rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 独立 Jayson 客户端验证成功、失败与通知混合批次的隔离。
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("client", target, "batch")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void jaysonHttpClientUnknownMethod() throws Exception {
        // 通过公开入口配置挂载点，对端只连接实际监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 先启动容器，再发布普通 Java 接口。
            server.start();
            server.publisher().rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 独立 Jayson 客户端验证不存在方法的标准错误码。
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("client", target, "missing")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void jaysonWebSocketClientPositionalArguments() throws Exception {
        // 通过公开入口配置挂载点，对端只连接实际监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc") //
                .option("upgrade", "websocket") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 先启动容器，再发布普通 Java 接口。
            server.start();
            server.publisher().rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 独立 Jayson 客户端验证位置参数、Unicode 正文与字符串请求 ID。
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "ws://127.0.0.1:" + port + "/rpc";
            try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("client", target, "positional")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void jaysonWebSocketClientNamedArguments() throws Exception {
        // 通过公开入口配置挂载点，对端只连接实际监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc") //
                .option("upgrade", "websocket") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 先启动容器，再发布普通 Java 接口。
            server.start();
            server.publisher().rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 独立 Jayson 客户端验证命名参数与数字请求 ID。
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "ws://127.0.0.1:" + port + "/rpc";
            try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("client", target, "named")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void jaysonWebSocketClientNotification() throws Exception {
        // 通过公开入口配置挂载点，对端只连接实际监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc") //
                .option("upgrade", "websocket") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 先启动容器，再发布普通 Java 接口。
            server.start();
            server.publisher().rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 独立 Jayson 客户端验证无 ID 通知执行且不产生 RPC 响应。
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "ws://127.0.0.1:" + port + "/rpc";
            try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("client", target, "notification")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void jaysonWebSocketClientMixedBatch() throws Exception {
        // 通过公开入口配置挂载点，对端只连接实际监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc") //
                .option("upgrade", "websocket") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 先启动容器，再发布普通 Java 接口。
            server.start();
            server.publisher().rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 独立 Jayson 客户端验证成功、失败与通知混合批次的隔离。
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "ws://127.0.0.1:" + port + "/rpc";
            try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("client", target, "batch")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void jaysonWebSocketClientUnknownMethod() throws Exception {
        // 通过公开入口配置挂载点，对端只连接实际监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc") //
                .option("upgrade", "websocket") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 先启动容器，再发布普通 Java 接口。
            server.start();
            server.publisher().rsfService(JsonRpcInteropService.class, new JsonRpcInteropServiceImpl()) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();

            // 独立 Jayson 客户端验证不存在方法的标准错误码。
            int port = server.getListenAddress("jsonrpc").getPort();
            String target = "ws://127.0.0.1:" + port + "/rpc";
            try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("client", target, "missing")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsJaysonHttpUnicode() throws Exception {
        // 客户端只配置出站能力，独立 Node.js 服务端持有监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http") //
                .protocol("jsonrpc") //
                .contextPath("/rpc");
        try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("server", "http"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅同名接口，地址来自独立服务端启动通知。
            client.start();
            MetBindInfo<JsonRpcInteropService> service = client.publisher() //
                    .rsfService(JsonRpcInteropService.class) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("jsonrpc", "127.0.0.1", port, "default");

            // Meteor 代理发起 RPC，独立 Jayson 服务端负责解析和响应。
            JsonRpcInteropService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsJaysonHttpNotification() throws Exception {
        // 客户端只配置出站能力，独立 Node.js 服务端持有监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http") //
                .protocol("jsonrpc") //
                .contextPath("/rpc");
        try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("server", "http"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅同名接口，地址来自独立服务端启动通知。
            client.start();
            MetBindInfo<JsonRpcInteropService> service = client.publisher() //
                    .rsfService(JsonRpcInteropService.class) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("jsonrpc", "127.0.0.1", port, "default");

            // 消息方法完整写出后返回，通过远端查询确认通知最终执行。
            JsonRpcInteropService remote = client.getRsfClient(address).getRemote(service);
            remote.notification("meteor-notification");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            String observed = remote.lastNotification();
            while (!StringUtils.equals("meteor-notification", observed) && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
                observed = remote.lastNotification();
            }
            assertEquals("meteor-notification", observed);
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsJaysonHttpError() throws Exception {
        // 客户端只配置出站能力，独立 Node.js 服务端持有监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http") //
                .protocol("jsonrpc") //
                .contextPath("/rpc");
        try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("server", "http"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅同名接口，地址来自独立服务端启动通知。
            client.start();
            MetBindInfo<JsonRpcInteropService> service = client.publisher() //
                    .rsfService(JsonRpcInteropService.class) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("jsonrpc", "127.0.0.1", port, "default");

            // 对端返回应用错误，调用方保留 JSON-RPC 错误码与消息。
            MetClient remote = client.getRsfClient(address);
            Class<?>[] parameters = new Class<?>[0];
            Object[] arguments = new Object[0];
            ExecutionException failure = assertThrows(ExecutionException.class, //
                    () -> remote.syncInvoke(service, "fail", parameters, arguments));
            assertTrue(failure.getCause() instanceof JsonRpcException);
            JsonRpcException error = (JsonRpcException) failure.getCause();
            assertEquals(-32042, error.getCode());
            assertEquals("Independent peer rejected request", error.getMessage());
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsJaysonWebSocketUnicode() throws Exception {
        // 客户端只配置出站能力，独立 Node.js 服务端持有监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http") //
                .protocol("jsonrpc") //
                .option("upgrade", "websocket") //
                .contextPath("/rpc");
        try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("server", "websocket"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅同名接口，地址来自独立服务端启动通知。
            client.start();
            MetBindInfo<JsonRpcInteropService> service = client.publisher() //
                    .rsfService(JsonRpcInteropService.class) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("jsonrpc", "127.0.0.1", port, "default");

            // Meteor 代理发起 RPC，独立 Jayson 服务端负责解析和响应。
            JsonRpcInteropService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("中文🙂", remote.echo("中文🙂"));
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsJaysonWebSocketNotification() throws Exception {
        // 客户端只配置出站能力，独立 Node.js 服务端持有监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http") //
                .protocol("jsonrpc") //
                .option("upgrade", "websocket") //
                .contextPath("/rpc");
        try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("server", "websocket"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅同名接口，地址来自独立服务端启动通知。
            client.start();
            MetBindInfo<JsonRpcInteropService> service = client.publisher() //
                    .rsfService(JsonRpcInteropService.class) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("jsonrpc", "127.0.0.1", port, "default");

            // 消息方法完整写出后返回，通过远端查询确认通知最终执行。
            JsonRpcInteropService remote = client.getRsfClient(address).getRemote(service);
            remote.notification("meteor-notification");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            String observed = remote.lastNotification();
            while (!StringUtils.equals("meteor-notification", observed) && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
                observed = remote.lastNotification();
            }
            assertEquals("meteor-notification", observed);
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsJaysonWebSocketError() throws Exception {
        // 客户端只配置出站能力，独立 Node.js 服务端持有监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.connector("http") //
                .protocol("jsonrpc") //
                .option("upgrade", "websocket") //
                .contextPath("/rpc");
        try (JsonRpcPeerProcess peer = JsonRpcPeerProcess.start("server", "websocket"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅同名接口，地址来自独立服务端启动通知。
            client.start();
            MetBindInfo<JsonRpcInteropService> service = client.publisher() //
                    .rsfService(JsonRpcInteropService.class) //
                    .aliasName("jsonrpc", "peer") //
                    .protocol("jsonrpc") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("jsonrpc", "127.0.0.1", port, "default");

            // 对端返回应用错误，调用方保留 JSON-RPC 错误码与消息。
            MetClient remote = client.getRsfClient(address);
            Class<?>[] parameters = new Class<?>[0];
            Object[] arguments = new Object[0];
            ExecutionException failure = assertThrows(ExecutionException.class, //
                    () -> remote.syncInvoke(service, "fail", parameters, arguments));
            assertTrue(failure.getCause() instanceof JsonRpcException);
            JsonRpcException error = (JsonRpcException) failure.getCause();
            assertEquals(-32042, error.getCode());
            assertEquals("Independent peer rejected request", error.getMessage());
        }
    }
}
