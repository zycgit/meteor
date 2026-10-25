/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.dubbo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.dubbo.interop.DubboInteropPerson;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 普通 Java 方法通过真实 Dubbo TCP 通道传递业务值。 */
public class DubboPojoScenarioTest {
    @Test(timeout = 15000)
    public void multipleArgumentsRetainTheirDeclaredPositions() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // 不同类型的参数根据方法签名依次编码，不借助 JSON 猜测参数位置。
            assertEquals("hello:3", remote.combine("hello", 3));
        }
    }

    @Test(timeout = 15000)
    public void stringOverloadUsesItsDeclaredSignature() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // 相同方法名按 String 参数签名选择实现。
            assertEquals("string:42", remote.overloaded("42"));
        }
    }

    @Test(timeout = 15000)
    public void primitiveOverloadUsesItsDeclaredSignature() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // primitive 参数不会被错误当作包装对象或字符串重载。
            assertEquals("int:42", remote.overloaded(42));
        }
    }

    @Test(timeout = 15000)
    public void noArgumentMethodReturnsItsBusinessValue() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // 零参数方法仍包含完整服务身份和方法名。
            assertEquals("empty", remote.noArgs());
        }
    }

    @Test(timeout = 15000)
    public void binaryParameterPreservesZeroAndSignedBytes() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // 二进制内容按原始字节往返，不经过字符编码。
            byte[] payload = { 0, 1, -1, 127, -128 };
            assertArrayEquals(payload, remote.binary(payload));
        }
    }

    @Test(timeout = 15000)
    public void dtoResultUsesTheDeclaredBusinessClass() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // DTO 还原为业务类型并携带服务端计算后的属性。
            DubboInteropPerson result = remote.person(new DubboInteropPerson("张三", 20));
            assertEquals("张三", result.getName());
            assertEquals(21, result.getAge());
        }
    }

    @Test(timeout = 15000)
    public void genericListRestoresEachBusinessElement() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // 集合保留泛型业务元素，不把 DTO 降级成 Map。
            List<DubboInteropPerson> input = List.of(new DubboInteropPerson("first", 2), //
                    new DubboInteropPerson("second", 5));
            List<DubboInteropPerson> result = remote.people(input);
            assertEquals(2, result.size());
            assertEquals("second", result.get(1).getName());
            assertEquals(6, result.get(1).getAge());
        }
    }

    @Test(timeout = 15000)
    public void mapResultPreservesItsValueTypes() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // Map 是普通业务数据，数字、布尔与 Unicode 值均保持各自语义。
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("name", "Meteor 中文");
            input.put("count", 42);
            input.put("enabled", true);
            Map<String, Object> result = remote.properties(input);
            assertEquals(input, result);
        }
    }

    @Test(timeout = 15000)
    public void nullArgumentAndResultRemainNull() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // null 不替换为空字符串，也不会破坏参数签名解析。
            assertNull(remote.nullable(null));
        }
    }

    @Test(timeout = 15000)
    public void voidOrdinaryCallWaitsForAResponse() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // void 仍是普通双向 RPC，Future 取得响应后以空业务结果完成。
            MetFuture pending = caller.asyncInvoke(service, "nothing", new Class<?>[0], new Object[0]);
            assertNull(pending.getData(5, TimeUnit.SECONDS));
            assertFalse(pending.getRequest().isMessage());
        }
    }

    @Test(timeout = 15000)
    public void businessFailureDoesNotPoisonFollowingCalls() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // 业务异常通过响应交付，不能降级成连接错误或无关超时。
            MetFuture pending = caller.asyncInvoke(service, "fail", new Class<?>[] { String.class }, //
                    new Object[] { "中文" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(5, TimeUnit.SECONDS);
            });
            Throwable business = failure.getCause();
            while (business != null && !(business instanceof IllegalArgumentException)) {
                business = business.getCause();
            }
            assertNotNull("Original business exception must remain observable", business);
            assertEquals("dubbo failure 中文", business.getMessage());

            // 同一客户端的后续请求仍可正常工作。
            assertEquals("following", remote.echo("following"));
        }
    }

    @Test(timeout = 15000)
    public void concurrentRequestsCorrelateResponsesByRequestId() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // 同一长连接并发提交请求，响应必须交回各自的 Future。
            List<MetFuture> pending = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                MetFuture call = caller.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                        new Object[] { "request-" + index });
                pending.add(call);
            }
            for (int index = 0; index < pending.size(); index++) {
                assertEquals("request-" + index, pending.get(index).getData(5, TimeUnit.SECONDS));
            }
        }
    }

    private MetConfiguration configuration(boolean listener) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo");
        configuration.setDefaultTimeout(5000);
        var tcp = configuration.connector("tcp");
        if (listener) {
            tcp.bind("127.0.0.1", 0);
        }
        tcp.protocol("dubbo");
        return configuration;
    }
}
