/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.xmlrpc;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoService;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoServiceImpl;
import net.hasor.example.protocol.xmlrpc.XmlRpcPerson;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.xmlrpc.XmlRpcException;
import org.junit.Test;
import static org.junit.Assert.*;

/** Python 标准库与 Meteor 的独立双向互通；每种线协议类型独立验证。 */
public class XmlRpcInteropTest {

    @Test(timeout = 30000)
    public void pythonClientCallsMeteorUnicodeString() throws Exception {
        // 配置实际监听端口和协议挂载，Python 直接使用标准库客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，没有单独的对端适配服务。
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();

            // 独立实现验证Unicode 字符和 XML 转义，Python 内断言线协议解析结果。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("client", target, "string")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonClientCallsMeteorInteger() throws Exception {
        // 配置实际监听端口和协议挂载，Python 直接使用标准库客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，没有单独的对端适配服务。
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();

            // 独立实现验证有序整数参数与整数结果，Python 内断言线协议解析结果。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("client", target, "integer")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonClientCallsMeteorBoolean() throws Exception {
        // 配置实际监听端口和协议挂载，Python 直接使用标准库客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，没有单独的对端适配服务。
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();

            // 独立实现验证布尔值，Python 内断言线协议解析结果。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("client", target, "boolean")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonClientCallsMeteorDouble() throws Exception {
        // 配置实际监听端口和协议挂载，Python 直接使用标准库客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，没有单独的对端适配服务。
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();

            // 独立实现验证浮点数，Python 内断言线协议解析结果。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("client", target, "double")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonClientCallsMeteorBinary() throws Exception {
        // 配置实际监听端口和协议挂载，Python 直接使用标准库客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，没有单独的对端适配服务。
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();

            // 独立实现验证base64 二进制，Python 内断言线协议解析结果。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("client", target, "base64")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonClientCallsMeteorDateTime() throws Exception {
        // 配置实际监听端口和协议挂载，Python 直接使用标准库客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，没有单独的对端适配服务。
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();

            // 独立实现验证无时区日期按 UTC 解释，Python 内断言线协议解析结果。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("client", target, "datetime")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonClientCallsMeteorTypedArray() throws Exception {
        // 配置实际监听端口和协议挂载，Python 直接使用标准库客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，没有单独的对端适配服务。
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();

            // 独立实现验证结构体数组，Python 内断言线协议解析结果。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("client", target, "array")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonClientCallsMeteorNestedStruct() throws Exception {
        // 配置实际监听端口和协议挂载，Python 直接使用标准库客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，没有单独的对端适配服务。
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();

            // 独立实现验证嵌套结构体，Python 内断言线协议解析结果。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("client", target, "struct")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonClientCallsMeteorApplicationFault() throws Exception {
        // 配置实际监听端口和协议挂载，Python 直接使用标准库客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，没有单独的对端适配服务。
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();

            // 独立实现验证应用故障码与消息，Python 内断言线协议解析结果。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("client", target, "fault")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonClientCallsMeteorNoArguments() throws Exception {
        // 配置实际监听端口和协议挂载，Python 直接使用标准库客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，没有单独的对端适配服务。
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();

            // 独立实现验证无参调用，Python 内断言线协议解析结果。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("client", target, "noargs")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void pythonClientCallsMeteorOptionalNil() throws Exception {
        // 配置实际监听端口和协议挂载，Python 直接使用标准库客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc") //
                .option("allowNil", true) //
                .contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，没有单独的对端适配服务。
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();

            // 独立实现验证显式开启的 nil 扩展，Python 内断言线协议解析结果。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://127.0.0.1:" + port + "/rpc";
            try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("client", target, "nil")) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsPythonServerUnicodeString() throws Exception {
        // Python 标准库监听端口，Meteor 只配置出站传输和协议。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("server"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，目标地址来自独立服务端的启动消息。
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();
            InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", port, "default");

            // 通过公开客户端验证Unicode 字符和 XML 转义，对端完成真正的 XML 编解码。
            XmlRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            String value = "中文🙂 <tag a=\"quoted\"> & escaped";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsPythonServerInteger() throws Exception {
        // Python 标准库监听端口，Meteor 只配置出站传输和协议。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("server"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，目标地址来自独立服务端的启动消息。
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();
            InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", port, "default");

            // 通过公开客户端验证有序整数参数与整数结果，对端完成真正的 XML 编解码。
            XmlRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals(16, remote.subtract(21, 5));
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsPythonServerBoolean() throws Exception {
        // Python 标准库监听端口，Meteor 只配置出站传输和协议。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("server"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，目标地址来自独立服务端的启动消息。
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();
            InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", port, "default");

            // 通过公开客户端验证布尔值，对端完成真正的 XML 编解码。
            XmlRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertFalse(remote.negate(true));
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsPythonServerDouble() throws Exception {
        // Python 标准库监听端口，Meteor 只配置出站传输和协议。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("server"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，目标地址来自独立服务端的启动消息。
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();
            InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", port, "default");

            // 通过公开客户端验证浮点数，对端完成真正的 XML 编解码。
            XmlRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals(6.25, remote.half(12.5), 0.0);
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsPythonServerBinary() throws Exception {
        // Python 标准库监听端口，Meteor 只配置出站传输和协议。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("server"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，目标地址来自独立服务端的启动消息。
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();
            InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", port, "default");

            // 通过公开客户端验证base64 二进制，对端完成真正的 XML 编解码。
            XmlRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            byte[] value = new byte[] { (byte) 255, 0, 65 };
            assertArrayEquals(value, remote.binary(value));
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsPythonServerDateTime() throws Exception {
        // Python 标准库监听端口，Meteor 只配置出站传输和协议。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("server"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，目标地址来自独立服务端的启动消息。
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();
            InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", port, "default");

            // 通过公开客户端验证无时区日期按 UTC 解释，对端完成真正的 XML 编解码。
            XmlRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            Date value = Date.from(Instant.parse("2026-10-16T12:34:56Z"));
            assertEquals(value, remote.date(value));
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsPythonServerTypedArray() throws Exception {
        // Python 标准库监听端口，Meteor 只配置出站传输和协议。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("server"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，目标地址来自独立服务端的启动消息。
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();
            InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", port, "default");

            // 通过公开客户端验证结构体数组，对端完成真正的 XML 编解码。
            XmlRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            XmlRpcPerson first = new XmlRpcPerson();
            first.setName("甲");
            first.setAge(31);
            XmlRpcPerson second = new XmlRpcPerson();
            second.setName("乙");
            second.setAge(28);
            List<XmlRpcPerson> result = remote.people(List.of(first, second));
            assertEquals(2, result.size());
            assertEquals("甲", result.get(0).getName());
            assertEquals(31, result.get(0).getAge());
            assertEquals("乙", result.get(1).getName());
            assertEquals(28, result.get(1).getAge());
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsPythonServerNestedStruct() throws Exception {
        // Python 标准库监听端口，Meteor 只配置出站传输和协议。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("server"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，目标地址来自独立服务端的启动消息。
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();
            InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", port, "default");

            // 通过公开客户端验证嵌套结构体，对端完成真正的 XML 编解码。
            XmlRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            Map<String, Object> nested = Map.of("name", "<标签>&");
            Map<String, Object> value = Map.of("count", 7, "enabled", true, "nested", nested);
            assertEquals(value, remote.struct(value));
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsPythonServerApplicationFault() throws Exception {
        // Python 标准库监听端口，Meteor 只配置出站传输和协议。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("server"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，目标地址来自独立服务端的启动消息。
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();
            InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", port, "default");

            // 通过公开客户端验证应用故障码与消息，对端完成真正的 XML 编解码。
            MetClient remote = client.getRsfClient(address);
            Class<?>[] types = new Class<?>[] { String.class };
            Object[] arguments = new Object[] { "独立调用失败 <&>" };
            ExecutionException failure = assertThrows(ExecutionException.class, //
                    () -> remote.syncInvoke(service, "fail", types, arguments));
            assertTrue(failure.getCause() instanceof XmlRpcException);
            XmlRpcException fault = (XmlRpcException) failure.getCause();
            assertEquals(42, fault.getCode());
            assertEquals("独立调用失败 <&>", fault.getMessage());
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsPythonServerNoArguments() throws Exception {
        // Python 标准库监听端口，Meteor 只配置出站传输和协议。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("xmlrpc") //
                .contextPath("/rpc");
        try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("server"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，目标地址来自独立服务端的启动消息。
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();
            InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", port, "default");

            // 通过公开客户端验证无参调用，对端完成真正的 XML 编解码。
            XmlRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("ready", remote.current());
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsPythonServerOptionalNil() throws Exception {
        // Python 标准库监听端口，Meteor 只配置出站传输和协议。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("xmlrpc") //
                .option("allowNil", true) //
                .contextPath("/rpc");
        try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("server"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 容器启动后订阅普通接口，目标地址来自独立服务端的启动消息。
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();
            InterAddress address = new InterAddress("xmlrpc", "127.0.0.1", port, "default");

            // 通过公开客户端验证显式开启的 nil 扩展，对端完成真正的 XML 编解码。
            XmlRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertNull(remote.echo(null));
        }
    }

    @Test(timeout = 30000)
    public void pythonClientCallsMeteorOverHttps() throws Exception {
        // 用实际测试 CA 开启 HTTPS，独立 Python 客户端校验服务端证书和主机名。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        XmlRpcTlsUtils.serverTls(listener, 1, "NONE");
        listener.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 启动后发布接口，服务仍由同一个 XML-RPC 插件处理。
            server.start();
            server.publisher().rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();

            // Python 不跳过证书校验，使用插件测试资源中的公开测试 CA。
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "https://localhost:" + port + "/rpc";
            String trustFile = this.pemFile("trust.pem");
            try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("client", target, "string", trustFile)) {
                peer.requireSuccess();
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorCallsPythonServerOverHttps() throws Exception {
        // Python 用测试服务端证书提供 HTTPS，Meteor 使用同一 CA 校验证书。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        ConnectorSettings sender = configuration.connector("http").option("httpVersion", 1);
        XmlRpcTlsUtils.clientTls(sender, 1, null);
        sender.protocol("xmlrpc").addressScheme("https").contextPath("/rpc");
        String certificate = this.pemFile("server-chain.pem");
        String privateKey = this.pemFile("server-key.pem");
        try (XmlRpcPeerProcess peer = XmlRpcPeerProcess.start("server", certificate, privateKey); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 启动后订阅接口，不手工构造协议会话或 XML 报文。
            client.start();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .protocol("xmlrpc") //
                    .register();
            InterAddress address = new InterAddress("https", "localhost", port, "default");

            // 动态代理通过加密连接调用独立 Python 服务端。
            XmlRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("中文🙂 <&>", remote.echo("中文🙂 <&>"));
        }
    }

    private String pemFile(String name) throws Exception {
        String resource = "/tls/acceptance/" + name;
        return Path.of(getClass().getResource(resource).toURI()).toString();
    }
}
