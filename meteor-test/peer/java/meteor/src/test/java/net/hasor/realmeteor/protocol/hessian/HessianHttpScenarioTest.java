/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.hessian.HessianDemoService;
import net.hasor.example.protocol.hessian.HessianDemoServiceImpl;
import net.hasor.example.protocol.hessian.HessianPerson;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

@NxnRpc(name = "hessian", version = "2")
public class HessianHttpScenarioTest {


    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void integerParameterAndReturnUseIntegerValues() throws Exception {
        // int 参数与返回值经过公开代理的 Hessian 转换链路。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两个容器，再发布并订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 使用独立客户端上下文发起实际网络请求，验证调用结果。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertEquals(Integer.MIN_VALUE, remote.number(Integer.MIN_VALUE));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void longParameterAndReturnKeepTheirFullPrecision() throws Exception {
        // Hessian long 不经过 JSON 数字转换，保留 64 位整数精度。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两个容器，再发布并订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 使用独立客户端上下文发起实际网络请求，验证调用结果。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            long value = 9_007_199_254_740_993L;
            assertEquals(value, remote.longValue(value));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void doubleParameterAndReturnKeepTheirValue() throws Exception {
        // Hessian double 在参数与返回值间保留浮点值。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两个容器，再发布并订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 使用独立客户端上下文发起实际网络请求，验证调用结果。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertEquals(12345.625, remote.decimal(12345.625), 0.0);
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void booleanParameterAndReturnKeepTheirValue() throws Exception {
        // Hessian 布尔值按业务接口的 boolean 类型传递。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两个容器，再发布并订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 使用独立客户端上下文发起实际网络请求，验证调用结果。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertTrue(remote.enabled(true));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void binaryParameterAndReturnKeepEveryByte() throws Exception {
        // 二进制内容直接按 byte[] 编码，涵盖符号位与零字节。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两个容器，再发布并订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 使用独立客户端上下文发起实际网络请求，验证调用结果。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            byte[] value = { 0, 1, 127, (byte) 128, (byte) 255 };
            assertArrayEquals(value, remote.binary(value));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void dateParameterAndReturnKeepMillisecondPrecision() throws Exception {
        // Hessian 日期使用毫秒时间，保留非整秒值。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两个容器，再发布并订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 使用独立客户端上下文发起实际网络请求，验证调用结果。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            Date value = new Date(1_782_235_245_678L);
            assertEquals(value, remote.date(value));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void typedCollectionKeepsItsBusinessObjects() throws Exception {
        // 集合元素按 Hessian 类型信息恢复为业务 DTO。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两个容器，再发布并订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 使用独立客户端上下文发起实际网络请求，验证调用结果。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            HessianPerson person = new HessianPerson();
            person.setName("Meteor");
            person.setAge(11);
            List<HessianPerson> people = new ArrayList<>();
            people.add(person);
            List<HessianPerson> result = remote.people(people);
            assertEquals(1, result.size());
            assertEquals("Meteor", result.get(0).getName());
            assertEquals(11, result.get(0).getAge());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nestedMapKeepsItsValuesAndTypes() throws Exception {
        // 动态 Map 保留嵌套集合与不同基础类型。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两个容器，再发布并订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 使用独立客户端上下文发起实际网络请求，验证调用结果。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", "Meteor");
            value.put("count", 7L);
            value.put("active", true);
            List<String> tags = new ArrayList<>();
            tags.add("rpc");
            tags.add("hessian");
            value.put("tags", tags);
            assertEquals(value, remote.struct(value));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nullParameterAndReturnRemainNull() throws Exception {
        // Hessian 原生 null 不需要附加扩展配置。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两个容器，再发布并订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 使用独立客户端上下文发起实际网络请求，验证调用结果。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertNull(remote.nullable(null));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sameArityOverloadsUseTheirDeclaredParameterTypes() throws Exception {
        // 相同参数数量的重载通过声明类型区分。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两个容器，再发布并订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 使用独立客户端上下文发起实际网络请求，验证调用结果。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertEquals("text:17", remote.overloaded("17"));
            assertEquals("number:17", remote.overloaded(17));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void zeroArgumentMethodIsInvocable() throws Exception {
        // 无参数方法使用普通 Hessian 调用与结果。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两个容器，再发布并订阅同一个普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 使用独立客户端上下文发起实际网络请求，验证调用结果。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertEquals("ready", remote.current());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void applicationFaultReachesTheCallerAsAnException() throws Exception {
        // 业务异常应作为 Hessian fault 返回，不是网络错误或超时。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 异步调用保持异常链可见，同时验证明确的业务消息。
            MetClient remote = client.getRsfClient(server.getListenAddress("hessian"));
            Class<?>[] types = { String.class };
            Object[] arguments = { "business rejected" };
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                remote.syncInvoke(service, "fail", types, arguments);
            });
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertEquals("business rejected", failure.getCause().getMessage());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void incomingHessianCallsExecuteThePublishedServiceFilter() throws Exception {
        // 用户服务过滤器必须经过 Dispatcher 的正式调用链。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration();
        AtomicInteger filtered = new AtomicInteger();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian") //
                    .bindFilter("observe", (request, response, chain) -> {
                        filtered.incrementAndGet();
                        chain.doFilter(request, response);
                    }).register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 协议转换不能绕过过滤器直接调用业务对象。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            assertEquals("filtered", remote.echo("filtered"));
            assertEquals(1, filtered.get());
        }
    }



    private MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/rpc");
        return configuration;
    }

    private MetConfiguration clientConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.connector("http").protocol("hessian").contextPath("/rpc");
        return configuration;
    }
}
