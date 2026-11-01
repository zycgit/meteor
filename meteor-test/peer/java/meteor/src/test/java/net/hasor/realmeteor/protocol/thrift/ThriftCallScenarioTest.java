/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.Choice;
import net.hasor.example.protocol.thrift.generated.DeclaredFailure;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.example.protocol.thrift.generated.Sample;
import net.hasor.example.protocol.thrift.generated.Shade;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.apache.thrift.TApplicationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 每个场景从业务接口发布开始，所有参数与结果均经过真实 TCP 链路。 */
@NxnRpc(name = "thrift")
public class ThriftCallScenarioTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void binaryUnframedTypedProxyCallsOrdinaryService() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", false);
        MetConfiguration outgoing = this.outgoing("binary", false);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // binary 编码与 Unframed 传输由调用配置显式选定。
            assertEquals("Thrift 中文", remote.echo("Thrift 中文"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void compactFramedTypedProxyCallsOrdinaryService() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("compact", true);
        MetConfiguration outgoing = this.outgoing("compact", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // compact 编码与 Framed 传输由调用配置显式选定。
            assertEquals("Thrift 中文", remote.echo("Thrift 中文"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void compactUnframedTypedProxyCallsOrdinaryService() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("compact", false);
        MetConfiguration outgoing = this.outgoing("compact", false);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // compact 编码与 Unframed 传输由调用配置显式选定。
            assertEquals("Thrift 中文", remote.echo("Thrift 中文"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void jsonFramedTypedProxyCallsOrdinaryService() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("json", true);
        MetConfiguration outgoing = this.outgoing("json", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // json 编码与 Framed 传输由调用配置显式选定。
            assertEquals("Thrift 中文", remote.echo("Thrift 中文"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void jsonUnframedTypedProxyCallsOrdinaryService() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("json", false);
        MetConfiguration outgoing = this.outgoing("json", false);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // json 编码与 Unframed 传输由调用配置显式选定。
            assertEquals("Thrift 中文", remote.echo("Thrift 中文"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void parameterDeclarationOrderUsesGeneratedFieldIds() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public int add(int left, int right) {
                return left * 100 + right;
            }
        };
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // IDL 参数声明顺序为 left(7)、right(2)，编码不能按字段 ID 排序后错配参数。
            // 非交换业务返回值使 left/right 对调必然改变结果。
            assertEquals(1923, remote.add(19, 23));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void structRoundTripPreservesEveryScalarAndNestedField() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // 一个完整 DTO 覆盖标量、二进制、嵌套容器、枚举、union、UUID 与有值 optional。
            Sample expected = this.sample();
            Sample actual = remote.mirror(expected);
            assertEquals(expected, actual);
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unsetOptionalFieldsRemainUnset() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // 未设置状态与零值、空字符串必须区分。
            Sample actual = remote.mirror(new Sample());
            assertFalse(actual.isSetNote());
            assertFalse(actual.isSetOptionalCount());
            assertFalse(actual.isSetChoice());
            assertFalse(actual.isSetRequestId());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void explicitOptionalZeroAndEmptyTextRemainSet() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // 显式设置的零值和空文本不能被当作缺省字段丢弃。
            Sample request = new Sample();
            request.setOptionalCount(0);
            request.setNote("");
            Sample actual = remote.mirror(request);
            assertTrue(actual.isSetOptionalCount());
            assertEquals(0, actual.getOptionalCount());
            assertTrue(actual.isSetNote());
            assertEquals("", actual.getNote());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void idlDefaultsSurviveAResultWithoutExplicitAssignments() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // 服务返回未显式赋值的生成 DTO，IDL 默认值由官方结构自身保留。
            Sample actual = remote.defaults();
            assertEquals(17, actual.getDefaultCount());
            assertEquals(Shade.BLUE, actual.getShade());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void listRetainsOrderUnicodeAndEmptyElements() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // 列表顺序和元素内容属于同一次返回值的契约。
            List<String> expected = Arrays.asList("first", "汉字", "", "last");
            assertEquals(expected, remote.echoList(expected));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void mapRetainsTypedKeysAndIntegerValues() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // 容器由生成参数结构编码，不能退化成 JSON 字符串。
            Map<String, Integer> expected = new LinkedHashMap<>();
            expected.put("中文", -7);
            expected.put("maximum", Integer.MAX_VALUE);
            assertEquals(expected, remote.echoMap(expected));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void setRetainsLongValues() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // i64 集合包含超出 i32 范围的元素。
            Set<Long> expected = new LinkedHashSet<>(Arrays.asList(7L, -9L, Long.MAX_VALUE));
            assertEquals(expected, remote.echoSet(expected));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void binaryRetainsBytesWithoutReadingOutsideTheBufferSlice() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // ByteBuffer 的 position/limit 确定本次二进制参数，而不是整个 backing array。
            ByteBuffer expected = ByteBuffer.wrap(new byte[] { 9, 0, 127, -128, -1, 8 });
            expected.position(1);
            expected.limit(5);
            ByteBuffer actual = remote.echoBinary(expected);
            assertEquals(expected, actual);
            assertEquals(1, expected.position());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void enumRetainsItsGeneratedConstant() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // 枚举通过其 IDL 整数值往返为同一生成常量。
            assertEquals(Shade.RED, remote.echoEnum(Shade.RED));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unionRetainsTheSelectedAlternative() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // union 仅携带选中的 i64 分支。
            Choice expected = Choice.count(Long.MAX_VALUE);
            assertEquals(expected, remote.echoUnion(expected));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void uuidRetainsBothLongHalves() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // UUID 是 Thrift 0.25 的类型，不经业务层字符串转换。
            UUID expected = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
            assertEquals(expected, remote.echoUuid(expected));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void extendedServiceInvokesInheritedAndDeclaredMethods() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // Extended 服务元数据同时包含基类方法和自己声明的方法。
            assertEquals("base", remote.echo("base"));
            assertEquals("extended", remote.extended("extended"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void declaredFailureReachesTypedCallerAsGeneratedException() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // IDL throws 字段承载生成异常，调用适配器必须保留它的 Java 类型和字段。
            DeclaredFailure failure = assertThrows(DeclaredFailure.class, () -> remote.fail("conflict"));
            assertEquals(409, failure.getCode());
            assertEquals("conflict", failure.getReason());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unexpectedFailureReachesTypedCallerAsApplicationException() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // 非 IDL 声明的业务异常被封装为标准 INTERNAL_ERROR。
            TApplicationException failure = assertThrows(TApplicationException.class, () -> remote.crash("broken"));
            assertEquals(TApplicationException.INTERNAL_ERROR, failure.getType());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void directCoreFutureRetainsItsExecutionExceptionContract() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // 插件适配器不改变内核 Future 的既有失败包装契约。
            Class<?>[] types = { String.class };
            Object[] arguments = { "conflict" };
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                connection.asyncInvoke(service, "fail", types, arguments).getData(5, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof DeclaredFailure);
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void ordinaryVoidCompletesWithAnAcknowledgedResult() throws Exception {
        // 验证 IDL 的业务类型在真实网络往返后保留语义。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再发布实现与订阅相同 IDL 契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));
            ThriftDemoService remote = new ThriftClient(connection).getRemote(service);

            // 普通 void 仍由请求响应链路完成，getData 成功证明收到结果。
            Object result = connection.asyncInvoke(service, "ping", new Class<?>[0], new Object[0]) //
                    .getData(5, TimeUnit.SECONDS);
            assertNull(result);
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void onewayCompletesWithoutWaitingForBusinessCompletion() throws Exception {
        // 将通知业务保持在途，证明消息调用完成不依赖服务端返回业务结果。
        MetConfiguration incoming = this.incoming("binary", true);
        MetConfiguration outgoing = this.outgoing("binary", true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        ThriftDemoServiceImpl implementation = new ThriftDemoServiceImpl() {
            @Override
            public void notify(String value) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Notification release timed out");
                    }
                    super.notify(value);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                } finally {
                    completed.countDown();
                }
            }
        };
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, implementation) //
                    .bindResource("thrift", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", resource).register();
            MetClient connection = client.getRsfClient(server.getListenAddress("thrift"));

            // 通知送达后，其发送 Future 已完成，但业务仍等候测试释放。
            try {
                Class<?>[] types = { String.class };
                Object result = connection.asyncInvoke(service, "notify", types, new Object[] { "notice" }) //
                        .getData(5, TimeUnit.SECONDS);
                assertNull(result);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, completed.getCount());
            } finally {
                release.countDown();
            }

            // 使用明确完成信号观察消息已消费，避免用固定休眠猜测时序。
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(1, implementation.notificationCount());
        }
    }

    private MetConfiguration incoming(String encoding, boolean framed) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.connector("tcp").bind("127.0.0.1", 0) //
                .protocol("thrift").option("encoding", encoding).option("framed", framed);
        return configuration;
    }

    private MetConfiguration outgoing(String encoding, boolean framed) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.connector("tcp").protocol("thrift") //
                .option("encoding", encoding).option("framed", framed);
        return configuration;
    }

    private Sample sample() {
        Sample value = new Sample();
        value.setId(5_000_000_007L);
        value.setText("Ada 汉字");
        value.setEnabled(true);
        value.setSmall((short) -1234);
        value.setCount(-123_456);
        value.setRatio(42.5);
        value.setPayload(new byte[] { 0, 1, 127, -128, -1 });
        value.setTags(Arrays.asList("alpha", "汉字"));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("language", "中文");
        value.setLabels(labels);
        value.setCodes(new LinkedHashSet<>(Arrays.asList(7, -9)));
        value.setTiny((byte) -17);
        value.setNote("present");
        value.setOptionalCount(0);
        value.setDefaultCount(29);
        value.setShade(Shade.RED);
        value.setChoice(Choice.text("selected"));
        value.setRequestId(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"));
        return value;
    }
}
