/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import com.caucho.hessian.client.HessianProxyFactory;
import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import net.hasor.example.protocol.hessian.HessianInteropDemo;
import net.hasor.example.protocol.hessian.HessianInteropDemoImpl;
import net.hasor.example.protocol.hessian.HessianInteropException;
import net.hasor.example.protocol.hessian.HessianInteropPerson;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** 官方 Caucho 与 Meteor 双向互通；版本矩阵的每个方法只验证一个场景。 */
@RunWith(Parameterized.class)
public class CauchoHessianInteropTest {
    private final int version;

    public CauchoHessianInteropTest(int version) {
        this.version = version;
    }

    @Parameterized.Parameters(name = "requestVersion={0}")
    public static Object[][] versions() {
        return new Object[][] { { 1 }, { 2 } };
    }

    @Test(timeout = 30000)
    public void cauchoClientCallsUnicodeString() throws Exception {
        // 配置 Meteor 的 HTTP 监听和 Hessian 挂载，不直接创建协议会话。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (MetContext server = configuration.buildContext()) {
            // 服务容器启动后发布普通 Java 接口，官方代理直接连接公开服务地址。
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            int port = server.getListenAddress("hessian").getPort();
            HessianInteropDemo remote = this.officialProxy("http://127.0.0.1:" + port + "/hessian/demo");

            // 验证Unicode 字符与补充平面字符。
            String expected = "Hessian 中文 🙂 <&>";
            String result = remote.echo(expected);
            assertEquals(expected, result);
        }
    }

    @Test(timeout = 30000)
    public void cauchoClientCallsZeroArgumentOverload() throws Exception {
        // 配置 Meteor 的 HTTP 监听和 Hessian 挂载，不直接创建协议会话。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (MetContext server = configuration.buildContext()) {
            // 服务容器启动后发布普通 Java 接口，官方代理直接连接公开服务地址。
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            int port = server.getListenAddress("hessian").getPort();
            HessianInteropDemo remote = this.officialProxy("http://127.0.0.1:" + port + "/hessian/demo");

            // 零参数重载的官方名称就是原方法名，不能误选其他有参重载。
            String result = remote.overloaded();
            assertEquals("zero-args", result);
        }
    }

    @Test(timeout = 30000)
    public void cauchoClientCallsStringOverload() throws Exception {
        // 配置 Meteor 的 HTTP 监听和 Hessian 挂载，不直接创建协议会话。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (MetContext server = configuration.buildContext()) {
            // 服务容器启动后发布普通 Java 接口，官方代理直接连接公开服务地址。
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            int port = server.getListenAddress("hessian").getPort();
            HessianInteropDemo remote = this.officialProxy("http://127.0.0.1:" + port + "/hessian/demo");

            // 验证同名字符串重载。
            String result = remote.overloaded("overload");
            assertEquals("string:overload", result);
        }
    }

    @Test(timeout = 30000)
    public void cauchoClientCallsIntegerOverload() throws Exception {
        // 配置 Meteor 的 HTTP 监听和 Hessian 挂载，不直接创建协议会话。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (MetContext server = configuration.buildContext()) {
            // 服务容器启动后发布普通 Java 接口，官方代理直接连接公开服务地址。
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            int port = server.getListenAddress("hessian").getPort();
            HessianInteropDemo remote = this.officialProxy("http://127.0.0.1:" + port + "/hessian/demo");

            // 验证同名整数重载。
            String result = remote.overloaded(37);
            assertEquals("int:37", result);
        }
    }

    @Test(timeout = 30000)
    public void cauchoClientCallsPojo() throws Exception {
        // 配置 Meteor 的 HTTP 监听和 Hessian 挂载，不直接创建协议会话。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (MetContext server = configuration.buildContext()) {
            // 服务容器启动后发布普通 Java 接口，官方代理直接连接公开服务地址。
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            int port = server.getListenAddress("hessian").getPort();
            HessianInteropDemo remote = this.officialProxy("http://127.0.0.1:" + port + "/hessian/demo");

            // 验证Serializable Java Bean 的类型与字段。
            HessianInteropPerson expected = this.person("李四", 27);
            HessianInteropPerson result = remote.person(expected);
            assertNotSame(expected, result);
            assertEquals("李四", result.getName());
            assertEquals(27, result.getAge());
        }
    }

    @Test(timeout = 30000)
    public void cauchoClientCallsNull() throws Exception {
        // 配置 Meteor 的 HTTP 监听和 Hessian 挂载，不直接创建协议会话。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (MetContext server = configuration.buildContext()) {
            // 服务容器启动后发布普通 Java 接口，官方代理直接连接公开服务地址。
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            int port = server.getListenAddress("hessian").getPort();
            HessianInteropDemo remote = this.officialProxy("http://127.0.0.1:" + port + "/hessian/demo");

            // 验证null 参数和返回值。
            assertNull(remote.nullable(null));
        }
    }

    @Test(timeout = 30000)
    public void cauchoClientCallsBinary() throws Exception {
        // 配置 Meteor 的 HTTP 监听和 Hessian 挂载，不直接创建协议会话。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (MetContext server = configuration.buildContext()) {
            // 服务容器启动后发布普通 Java 接口，官方代理直接连接公开服务地址。
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            int port = server.getListenAddress("hessian").getPort();
            HessianInteropDemo remote = this.officialProxy("http://127.0.0.1:" + port + "/hessian/demo");

            // 验证包含零字节和无符号高位值的二进制数据。
            byte[] expected = new byte[] { 0, 1, 127, (byte) 128, (byte) 255 };
            byte[] result = remote.binary(expected);
            assertArrayEquals(expected, result);
        }
    }

    @Test(timeout = 30000)
    public void cauchoClientCallsCollection() throws Exception {
        // 配置 Meteor 的 HTTP 监听和 Hessian 挂载，不直接创建协议会话。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (MetContext server = configuration.buildContext()) {
            // 服务容器启动后发布普通 Java 接口，官方代理直接连接公开服务地址。
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            int port = server.getListenAddress("hessian").getPort();
            HessianInteropDemo remote = this.officialProxy("http://127.0.0.1:" + port + "/hessian/demo");

            // 验证集合中各个有类型的业务对象。
            List<HessianInteropPerson> expected = new ArrayList<>();
            expected.add(this.person("甲", 21));
            expected.add(this.person("乙", 34));
            List<HessianInteropPerson> result = remote.collection(expected);
            assertEquals(2, result.size());
            assertEquals("甲", result.get(0).getName());
            assertEquals(21, result.get(0).getAge());
            assertEquals("乙", result.get(1).getName());
            assertEquals(34, result.get(1).getAge());
        }
    }

    @Test(timeout = 30000)
    public void cauchoClientCallsStructure() throws Exception {
        // 配置 Meteor 的 HTTP 监听和 Hessian 挂载，不直接创建协议会话。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (MetContext server = configuration.buildContext()) {
            // 服务容器启动后发布普通 Java 接口，官方代理直接连接公开服务地址。
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            int port = server.getListenAddress("hessian").getPort();
            HessianInteropDemo remote = this.officialProxy("http://127.0.0.1:" + port + "/hessian/demo");

            // 验证动态 Map 中的对象、二进制和 null。
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("person", this.person("Map DTO", 19));
            expected.put("bytes", new byte[] { 0, (byte) 255 });
            expected.put("missing", null);
            Map<String, Object> result = remote.structure(expected);
            assertEquals(3, result.size());
            HessianInteropPerson person = (HessianInteropPerson) result.get("person");
            assertEquals("Map DTO", person.getName());
            assertEquals(19, person.getAge());
            assertArrayEquals(new byte[] { 0, (byte) 255 }, (byte[]) result.get("bytes"));
            assertTrue(result.containsKey("missing"));
            assertNull(result.get("missing"));
        }
    }

    @Test(timeout = 30000)
    public void cauchoClientCallsDeclaredException() throws Exception {
        // 配置 Meteor 的 HTTP 监听和 Hessian 挂载，不直接创建协议会话。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (MetContext server = configuration.buildContext()) {
            // 服务容器启动后发布普通 Java 接口，官方代理直接连接公开服务地址。
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            int port = server.getListenAddress("hessian").getPort();
            HessianInteropDemo remote = this.officialProxy("http://127.0.0.1:" + port + "/hessian/demo");

            // 验证声明异常的具体类型和消息。
            HessianInteropException failure = assertThrows(HessianInteropException.class, () -> remote.fail("拒绝访问"));
            assertEquals("拒绝访问", failure.getMessage());
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsUnicodeString() throws Exception {
        // 独立 HTTP 服务端使用 Caucho Skeleton；Meteor 只作为客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (CauchoHessianServer server = new CauchoHessianServer(new HessianInteropDemoImpl()); //
             MetContext client = configuration.buildContext()) {
            // 从 bootstrap 启动后订阅接口，通过真实网络连接官方实现。
            client.start();
            MetBindInfo<HessianInteropDemo> service = client.publisher().rsfService(HessianInteropDemo.class) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            InterAddress address = new InterAddress("hessian", "127.0.0.1", server.getPort(), "default");
            HessianInteropDemo remote = client.getRsfClient(address).getRemote(service);

            // 验证Unicode 字符与补充平面字符。
            String expected = "Hessian 中文 🙂 <&>";
            String result = remote.echo(expected);
            assertEquals(expected, result);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsZeroArgumentOverload() throws Exception {
        // 独立 HTTP 服务端使用 Caucho Skeleton；Meteor 只作为客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (CauchoHessianServer server = new CauchoHessianServer(new HessianInteropDemoImpl()); //
             MetContext client = configuration.buildContext()) {
            // 从 bootstrap 启动后订阅接口，通过真实网络连接官方实现。
            client.start();
            MetBindInfo<HessianInteropDemo> service = client.publisher().rsfService(HessianInteropDemo.class) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            InterAddress address = new InterAddress("hessian", "127.0.0.1", server.getPort(), "default");
            HessianInteropDemo remote = client.getRsfClient(address).getRemote(service);

            // 零参数重载的官方名称就是原方法名，不能误选其他有参重载。
            String result = remote.overloaded();
            assertEquals("zero-args", result);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsStringOverload() throws Exception {
        // 独立 HTTP 服务端使用 Caucho Skeleton；Meteor 只作为客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (CauchoHessianServer server = new CauchoHessianServer(new HessianInteropDemoImpl()); //
             MetContext client = configuration.buildContext()) {
            // 从 bootstrap 启动后订阅接口，通过真实网络连接官方实现。
            client.start();
            MetBindInfo<HessianInteropDemo> service = client.publisher().rsfService(HessianInteropDemo.class) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            InterAddress address = new InterAddress("hessian", "127.0.0.1", server.getPort(), "default");
            HessianInteropDemo remote = client.getRsfClient(address).getRemote(service);

            // 验证同名字符串重载。
            String result = remote.overloaded("overload");
            assertEquals("string:overload", result);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsIntegerOverload() throws Exception {
        // 独立 HTTP 服务端使用 Caucho Skeleton；Meteor 只作为客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (CauchoHessianServer server = new CauchoHessianServer(new HessianInteropDemoImpl()); //
             MetContext client = configuration.buildContext()) {
            // 从 bootstrap 启动后订阅接口，通过真实网络连接官方实现。
            client.start();
            MetBindInfo<HessianInteropDemo> service = client.publisher().rsfService(HessianInteropDemo.class) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            InterAddress address = new InterAddress("hessian", "127.0.0.1", server.getPort(), "default");
            HessianInteropDemo remote = client.getRsfClient(address).getRemote(service);

            // 验证同名整数重载。
            String result = remote.overloaded(37);
            assertEquals("int:37", result);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsPojo() throws Exception {
        // 独立 HTTP 服务端使用 Caucho Skeleton；Meteor 只作为客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (CauchoHessianServer server = new CauchoHessianServer(new HessianInteropDemoImpl()); //
             MetContext client = configuration.buildContext()) {
            // 从 bootstrap 启动后订阅接口，通过真实网络连接官方实现。
            client.start();
            MetBindInfo<HessianInteropDemo> service = client.publisher().rsfService(HessianInteropDemo.class) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            InterAddress address = new InterAddress("hessian", "127.0.0.1", server.getPort(), "default");
            HessianInteropDemo remote = client.getRsfClient(address).getRemote(service);

            // 验证Serializable Java Bean 的类型与字段。
            HessianInteropPerson expected = this.person("李四", 27);
            HessianInteropPerson result = remote.person(expected);
            assertNotSame(expected, result);
            assertEquals("李四", result.getName());
            assertEquals(27, result.getAge());
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsNull() throws Exception {
        // 独立 HTTP 服务端使用 Caucho Skeleton；Meteor 只作为客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (CauchoHessianServer server = new CauchoHessianServer(new HessianInteropDemoImpl()); //
             MetContext client = configuration.buildContext()) {
            // 从 bootstrap 启动后订阅接口，通过真实网络连接官方实现。
            client.start();
            MetBindInfo<HessianInteropDemo> service = client.publisher().rsfService(HessianInteropDemo.class) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            InterAddress address = new InterAddress("hessian", "127.0.0.1", server.getPort(), "default");
            HessianInteropDemo remote = client.getRsfClient(address).getRemote(service);

            // 验证null 参数和返回值。
            assertNull(remote.nullable(null));
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsBinary() throws Exception {
        // 独立 HTTP 服务端使用 Caucho Skeleton；Meteor 只作为客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (CauchoHessianServer server = new CauchoHessianServer(new HessianInteropDemoImpl()); //
             MetContext client = configuration.buildContext()) {
            // 从 bootstrap 启动后订阅接口，通过真实网络连接官方实现。
            client.start();
            MetBindInfo<HessianInteropDemo> service = client.publisher().rsfService(HessianInteropDemo.class) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            InterAddress address = new InterAddress("hessian", "127.0.0.1", server.getPort(), "default");
            HessianInteropDemo remote = client.getRsfClient(address).getRemote(service);

            // 验证包含零字节和无符号高位值的二进制数据。
            byte[] expected = new byte[] { 0, 1, 127, (byte) 128, (byte) 255 };
            byte[] result = remote.binary(expected);
            assertArrayEquals(expected, result);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsCollection() throws Exception {
        // 独立 HTTP 服务端使用 Caucho Skeleton；Meteor 只作为客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (CauchoHessianServer server = new CauchoHessianServer(new HessianInteropDemoImpl()); //
             MetContext client = configuration.buildContext()) {
            // 从 bootstrap 启动后订阅接口，通过真实网络连接官方实现。
            client.start();
            MetBindInfo<HessianInteropDemo> service = client.publisher().rsfService(HessianInteropDemo.class) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            InterAddress address = new InterAddress("hessian", "127.0.0.1", server.getPort(), "default");
            HessianInteropDemo remote = client.getRsfClient(address).getRemote(service);

            // 验证集合中各个有类型的业务对象。
            List<HessianInteropPerson> expected = new ArrayList<>();
            expected.add(this.person("甲", 21));
            expected.add(this.person("乙", 34));
            List<HessianInteropPerson> result = remote.collection(expected);
            assertEquals(2, result.size());
            assertEquals("甲", result.get(0).getName());
            assertEquals(21, result.get(0).getAge());
            assertEquals("乙", result.get(1).getName());
            assertEquals(34, result.get(1).getAge());
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsStructure() throws Exception {
        // 独立 HTTP 服务端使用 Caucho Skeleton；Meteor 只作为客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (CauchoHessianServer server = new CauchoHessianServer(new HessianInteropDemoImpl()); //
             MetContext client = configuration.buildContext()) {
            // 从 bootstrap 启动后订阅接口，通过真实网络连接官方实现。
            client.start();
            MetBindInfo<HessianInteropDemo> service = client.publisher().rsfService(HessianInteropDemo.class) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            InterAddress address = new InterAddress("hessian", "127.0.0.1", server.getPort(), "default");
            HessianInteropDemo remote = client.getRsfClient(address).getRemote(service);

            // 验证动态 Map 中的对象、二进制和 null。
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("person", this.person("Map DTO", 19));
            expected.put("bytes", new byte[] { 0, (byte) 255 });
            expected.put("missing", null);
            Map<String, Object> result = remote.structure(expected);
            assertEquals(3, result.size());
            HessianInteropPerson person = (HessianInteropPerson) result.get("person");
            assertEquals("Map DTO", person.getName());
            assertEquals(19, person.getAge());
            assertArrayEquals(new byte[] { 0, (byte) 255 }, (byte[]) result.get("bytes"));
            assertTrue(result.containsKey("missing"));
            assertNull(result.get("missing"));
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsDeclaredException() throws Exception {
        // 独立 HTTP 服务端使用 Caucho Skeleton；Meteor 只作为客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (CauchoHessianServer server = new CauchoHessianServer(new HessianInteropDemoImpl()); //
             MetContext client = configuration.buildContext()) {
            // 从 bootstrap 启动后订阅接口，通过真实网络连接官方实现。
            client.start();
            MetBindInfo<HessianInteropDemo> service = client.publisher().rsfService(HessianInteropDemo.class) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            InterAddress address = new InterAddress("hessian", "127.0.0.1", server.getPort(), "default");
            MetFuture result = client.getRsfClient(address).asyncInvoke(service, "fail", //
                    new Class<?>[] { String.class }, new Object[] { "拒绝访问" });

            // 异步 API 的失败结果保留 fault 的业务异常；不改变通用同步代理的包装约定。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            Throwable cause = failure.getCause();
            assertEquals(HessianInteropException.class, cause.getClass());
            assertEquals("拒绝访问", cause.getMessage());
        }
    }

    @Test(timeout = 30000)
    public void officialHessian1InputReadsStrictHessian1Reply() throws Throwable {
        // 官方代理的 H1 reader 选择有兼容问题；本例直接使用官方 H1 Input/Output。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (MetContext server = configuration.buildContext()) {
            // 服务发布仍走公开入口，正文来自 Caucho 的完整 Hessian 1 调用信封。
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            HessianOutput output = new HessianOutput(bytes);
            output.setVersion(1);
            output.call("person", new Object[] { this.person("Hessian 1", 18) });
            output.flush();
            int port = server.getListenAddress("hessian").getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/hessian/demo");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)) //
                    .header("Content-Type", "x-application/hessian") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();

            // HTTP 只承担传输；官方 HessianInput 必须还原 r01 响应的真实 POJO。
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals('r', response.body()[0]);
            assertEquals(1, response.body()[1]);
            HessianInput input = new HessianInput(new ByteArrayInputStream(response.body()));
            HessianInteropPerson result = (HessianInteropPerson) input.readReply(HessianInteropPerson.class);
            assertEquals("Hessian 1", result.getName());
            assertEquals(18, result.getAge());
        }
    }

    @Test(timeout = 30000)
    public void cauchoClientCallsDeclaredIOException() throws Exception {
        // 配置 Meteor 的 HTTP 监听和 Hessian 挂载，不直接创建协议会话。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (MetContext server = configuration.buildContext()) {
            // 服务容器启动后发布普通 Java 接口，官方代理直接连接公开服务地址。
            server.start();
            server.publisher().rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            int port = server.getListenAddress("hessian").getPort();
            HessianInteropDemo remote = this.officialProxy("http://127.0.0.1:" + port + "/hessian/demo");

            // 声明的 IOException 通过 fault 还原，不能被当成 HTTP 传输失败。
            IOException failure = assertThrows(IOException.class, () -> remote.failIo("读取失败"));
            assertEquals(IOException.class, failure.getClass());
            assertEquals("读取失败", failure.getMessage());
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCallsDeclaredIOException() throws Exception {
        // 独立 HTTP 服务端使用 Caucho Skeleton；Meteor 只作为客户端。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http") //
                .protocol("hessian").contextPath("/hessian").option("version", this.version);
        try (CauchoHessianServer server = new CauchoHessianServer(new HessianInteropDemoImpl()); //
             MetContext client = configuration.buildContext()) {
            // 从 bootstrap 启动后订阅接口，通过真实网络连接官方实现。
            client.start();
            MetBindInfo<HessianInteropDemo> service = client.publisher().rsfService(HessianInteropDemo.class) //
                    .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
            InterAddress address = new InterAddress("hessian", "127.0.0.1", server.getPort(), "default");
            MetFuture result = client.getRsfClient(address).asyncInvoke(service, "failIo", //
                    new Class<?>[] { String.class }, new Object[] { "读取失败" });

            // IOException 作为业务 fault 返回，保留原类型和消息而非包装为解码失败。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            Throwable cause = failure.getCause();
            assertEquals(IOException.class, cause.getClass());
            assertEquals("读取失败", cause.getMessage());
        }
    }

    private HessianInteropDemo officialProxy(String address) throws Exception {
        // Caucho 默认的 H1 请求使用 c02 协商 H2 响应；H2 模式使用 H02 请求。
        HessianProxyFactory factory = new HessianProxyFactory();
        factory.setHessian2Request(this.version == 2);
        factory.setHessian2Reply(true);
        factory.setOverloadEnabled(true);
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return (HessianInteropDemo) factory.create(HessianInteropDemo.class, address);
    }

    private HessianInteropPerson person(String name, int age) {
        HessianInteropPerson person = new HessianInteropPerson();
        person.setName(name);
        person.setAge(age);
        return person;
    }
}
