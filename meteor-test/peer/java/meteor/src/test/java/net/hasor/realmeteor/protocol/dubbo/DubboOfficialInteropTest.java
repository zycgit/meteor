/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.dubbo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.dubbo.interop.DubboInteropPerson;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

/** 原生 Dubbo 2/3 独立进程与 Meteor 正式入口的双向二进制协议互通。 */
public class DubboOfficialInteropTest {
    private static final String SERVICE = DubboInteropService.class.getName();

    @Test(timeout = 60000)
    public void officialDubbo3ClientCallsMeteorEcho() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=echo")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Dubbo 中文 🙂", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo3Echo() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                assertEquals("Dubbo 中文 🙂", remote.echo("Dubbo 中文 🙂"));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientCallsMeteorMultipleArguments() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=combine")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("hello:3", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo3MultipleArguments() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                assertEquals("hello:3", remote.combine("hello", 3));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientCallsMeteorStringOverload() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=string-overload")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("string:hello", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo3StringOverload() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                assertEquals("string:hello", remote.overloaded("hello"));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientCallsMeteorPrimitiveOverload() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=int-overload")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("int:7", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo3PrimitiveOverload() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                assertEquals("int:7", remote.overloaded(7));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientCallsMeteorPerson() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=person")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Ada", result.path("value").path("name").asText());
                assertEquals(42, result.path("value").path("age").asInt());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo3Person() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                DubboInteropPerson result = remote.person(new DubboInteropPerson("Ada", 37));
                assertEquals("Ada", result.getName());
                assertEquals(38, result.getAge());
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientCallsMeteorVoid() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=void")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertTrue(result.path("value").isNull());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo3Void() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                remote.nothing();
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientCallsMeteorNull() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=null")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertTrue(result.path("value").isNull());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo3Null() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                assertNull(remote.nullable(null));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientCallsMeteorBinary() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=binary")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertArrayEquals(new byte[] { 0, 1, 2, -1 }, result.path("value").binaryValue());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo3Binary() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                assertArrayEquals(new byte[] { 0, 1, -1 }, remote.binary(new byte[] { 0, 1, -1 }));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientReceivesMeteorBusinessFailure() throws Exception {
        // 服务发布保持普通 Java 异常语义，异常由协议编码回送。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方客户端须能识别业务异常，不能将协议失败当作成功结果。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=fault")) {
                JsonNode result = peer.awaitResult();
                String error = result.path("error").asText();
                assertTrue(result.toString(), error.contains("dubbo failure boom"));
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorReceivesOfficialDubbo3BusinessFailure() throws Exception {
        // 原生提供者抛出普通业务异常。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                MetClient remote = client.getRsfClient(address);

                // 通过异步入口读取 RPC 失败，验证原始业务异常类型和信息。
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "boom" };
                MetFuture pending = remote.asyncInvoke(service, "fail", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> pending.get(5, TimeUnit.SECONDS));
                IllegalArgumentException business = this.findCause(failure, IllegalArgumentException.class);
                assertNotNull(failure.toString(), business);
                assertEquals("dubbo failure boom", business.getMessage());
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientMultiplexesConcurrentMeteorCalls() throws Exception {
        // 同一个已发布服务承接并发请求，各调用按请求 ID 对应结果。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 同时提交独立参数，内部核对每个返回值。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=concurrent")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                JsonNode values = result.path("value");
                assertEquals(16, values.size());
                for (int index = 0; index < values.size(); index++) {
                    assertEquals("call-" + index, values.get(index).asText());
                }
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorMultiplexesConcurrentOfficialDubbo3Calls() throws Exception {
        // 独立官方提供者接收同一连接上的并发请求。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                MetClient remote = client.getRsfClient(address);

                // 一次提交全部请求后再等待，确保不是逐个同步调用。
                List<MetFuture> pending = new ArrayList<>();
                Class<?>[] parameterTypes = { String.class };
                for (int index = 0; index < 24; index++) {
                    Object[] arguments = { "concurrent-" + index };
                    pending.add(remote.asyncInvoke(service, "echo", parameterTypes, arguments));
                }

                // 逐个关联请求参数与返回值，检验复用通道的请求隔离。
                for (int index = 0; index < pending.size(); index++) {
                    MetResponse response = pending.get(index).get(5, TimeUnit.SECONDS);
                    assertEquals("concurrent-" + index, response.getData());
                }
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientKeepsConnectionAfterTimedOutMeteorCall() throws Exception {
        // 慢调用由官方 Caller 的期限结束，服务端仍会产生迟到结果。
        MetConfiguration configuration = this.configuration(true);
        configuration.setQueueMinPoolSize(2);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 对端超时后再次调用同一代理，验证失败不污染后续请求。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=timeout-isolation")) {
                JsonNode result = peer.awaitResult();
                String error = result.path("error").asText().toLowerCase(Locale.ROOT);
                assertTrue(result.toString(), error.contains("timeout"));
                assertEquals("after-timeout", result.path("recovered").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorKeepsConnectionAfterTimedOutOfficialDubbo3Call() throws Exception {
        // 慢调用发生超时后，同一个官方服务仍可继续处理后续请求。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").timeout(150).register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                MetClient remote = client.getRsfClient(address);

                // 外层等待比 RPC 期限更长，只接受真实 RPC 超时而非测试等待超时。
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "wait" };
                MetFuture pending = remote.asyncInvoke(service, "slow", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> pending.get(5, TimeUnit.SECONDS));
                assertTrue(failure.toString(), this.isRpcTimeout(failure));

                // 相同客户端发送新请求，迟到响应不能串入此调用。
                assertEquals("after-timeout", remote.getRemote(service).echo("after-timeout"));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientReceivesMeteorAttachments() throws Exception {
        // 服务过滤器通过公开选项接收请求附件并写出响应附件。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().bindFilter("official-metadata", (request, response, chain) -> {
                String value = request.getOption("x-dubbo-test");
                response.addOption("x-dubbo-test", value);
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 原生 RpcContext 读取 Meteor 回送的字段。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=metadata")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("metadata", result.path("metadata").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorReceivesOfficialDubbo3Attachments() throws Exception {
        // 官方服务通过原生 RpcContext 回送请求附件。
        try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                MetClient remote = client.getRsfClient(address).withOptions(Map.of("x-dubbo-test", "metadata"));

                // 异步入口保留完整响应，从公开选项读取回传附件。
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "metadata" };
                MetResponse response = remote.asyncInvoke(service, "metadata", parameterTypes, arguments) //
                        .get(5, TimeUnit.SECONDS);
                assertEquals("metadata", response.getData());
                assertEquals("metadata", response.getOption("x-dubbo-test"));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientCallsMeteorEcho() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=echo")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Dubbo 中文 🙂", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo2Echo() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                assertEquals("Dubbo 中文 🙂", remote.echo("Dubbo 中文 🙂"));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientCallsMeteorMultipleArguments() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=combine")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("hello:3", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo2MultipleArguments() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                assertEquals("hello:3", remote.combine("hello", 3));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientCallsMeteorStringOverload() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=string-overload")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("string:hello", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo2StringOverload() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                assertEquals("string:hello", remote.overloaded("hello"));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientCallsMeteorPrimitiveOverload() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=int-overload")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("int:7", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo2PrimitiveOverload() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                assertEquals("int:7", remote.overloaded(7));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientCallsMeteorPerson() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=person")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Ada", result.path("value").path("name").asText());
                assertEquals(42, result.path("value").path("age").asInt());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo2Person() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                DubboInteropPerson result = remote.person(new DubboInteropPerson("Ada", 37));
                assertEquals("Ada", result.getName());
                assertEquals(38, result.getAge());
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientCallsMeteorVoid() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=void")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertTrue(result.path("value").isNull());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo2Void() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                remote.nothing();
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientCallsMeteorNull() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=null")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertTrue(result.path("value").isNull());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo2Null() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                assertNull(remote.nullable(null));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientCallsMeteorBinary() throws Exception {
        // 通过正式 bootstrap 启动 TCP，随后发布普通 Java 服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 只存在于独立 JVM，由其真实 ReferenceConfig 调用 Meteor。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=binary")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertArrayEquals(new byte[] { 0, 1, 2, -1 }, result.path("value").binaryValue());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo2Binary() throws Exception {
        // 原生 SDK 在独立 JVM 中发布真实 Dubbo 二进制服务。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                // 启动后再订阅，客户端没有本地实现可走本地调用。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 通过公开代理调用并验证本场景的结果。
                assertArrayEquals(new byte[] { 0, 1, -1 }, remote.binary(new byte[] { 0, 1, -1 }));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientReceivesMeteorBusinessFailure() throws Exception {
        // 服务发布保持普通 Java 异常语义，异常由协议编码回送。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方客户端须能识别业务异常，不能将协议失败当作成功结果。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=fault")) {
                JsonNode result = peer.awaitResult();
                String error = result.path("error").asText();
                assertTrue(result.toString(), error.contains("dubbo failure boom"));
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorReceivesOfficialDubbo2BusinessFailure() throws Exception {
        // 原生提供者抛出普通业务异常。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                MetClient remote = client.getRsfClient(address);

                // 通过异步入口读取 RPC 失败，验证原始业务异常类型和信息。
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "boom" };
                MetFuture pending = remote.asyncInvoke(service, "fail", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> pending.get(5, TimeUnit.SECONDS));
                IllegalArgumentException business = this.findCause(failure, IllegalArgumentException.class);
                assertNotNull(failure.toString(), business);
                assertEquals("dubbo failure boom", business.getMessage());
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientMultiplexesConcurrentMeteorCalls() throws Exception {
        // 同一个已发布服务承接并发请求，各调用按请求 ID 对应结果。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SDK 同时提交独立参数，内部核对每个返回值。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=concurrent")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                JsonNode values = result.path("value");
                assertEquals(16, values.size());
                for (int index = 0; index < values.size(); index++) {
                    assertEquals("call-" + index, values.get(index).asText());
                }
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorMultiplexesConcurrentOfficialDubbo2Calls() throws Exception {
        // 独立官方提供者接收同一连接上的并发请求。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                MetClient remote = client.getRsfClient(address);

                // 一次提交全部请求后再等待，确保不是逐个同步调用。
                List<MetFuture> pending = new ArrayList<>();
                Class<?>[] parameterTypes = { String.class };
                for (int index = 0; index < 24; index++) {
                    Object[] arguments = { "concurrent-" + index };
                    pending.add(remote.asyncInvoke(service, "echo", parameterTypes, arguments));
                }

                // 逐个关联请求参数与返回值，检验复用通道的请求隔离。
                for (int index = 0; index < pending.size(); index++) {
                    MetResponse response = pending.get(index).get(5, TimeUnit.SECONDS);
                    assertEquals("concurrent-" + index, response.getData());
                }
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientKeepsConnectionAfterTimedOutMeteorCall() throws Exception {
        // 慢调用由官方 Caller 的期限结束，服务端仍会产生迟到结果。
        MetConfiguration configuration = this.configuration(true);
        configuration.setQueueMinPoolSize(2);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 对端超时后再次调用同一代理，验证失败不污染后续请求。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=timeout-isolation")) {
                JsonNode result = peer.awaitResult();
                String error = result.path("error").asText().toLowerCase(Locale.ROOT);
                assertTrue(result.toString(), error.contains("timeout"));
                assertEquals("after-timeout", result.path("recovered").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorKeepsConnectionAfterTimedOutOfficialDubbo2Call() throws Exception {
        // 慢调用发生超时后，同一个官方服务仍可继续处理后续请求。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").timeout(150).register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                MetClient remote = client.getRsfClient(address);

                // 外层等待比 RPC 期限更长，只接受真实 RPC 超时而非测试等待超时。
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "wait" };
                MetFuture pending = remote.asyncInvoke(service, "slow", parameterTypes, arguments);
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> pending.get(5, TimeUnit.SECONDS));
                assertTrue(failure.toString(), this.isRpcTimeout(failure));

                // 相同客户端发送新请求，迟到响应不能串入此调用。
                assertEquals("after-timeout", remote.getRemote(service).echo("after-timeout"));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientReceivesMeteorAttachments() throws Exception {
        // 服务过滤器通过公开选项接收请求附件并写出响应附件。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().bindFilter("official-metadata", (request, response, chain) -> {
                String value = request.getOption("x-dubbo-test");
                response.addOption("x-dubbo-test", value);
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 原生 RpcContext 读取 Meteor 回送的字段。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=metadata")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("metadata", result.path("metadata").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorReceivesOfficialDubbo2Attachments() throws Exception {
        // 官方服务通过原生 RpcContext 回送请求附件。
        try (DubboProcess peer = DubboProcess.startLegacy("--command=server", "--port=0", //
                "--group=interop", "--version=1.0.0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbo", peer.host(), port, "default");
                MetClient remote = client.getRsfClient(address).withOptions(Map.of("x-dubbo-test", "metadata"));

                // 异步入口保留完整响应，从公开选项读取回传附件。
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "metadata" };
                MetResponse response = remote.asyncInvoke(service, "metadata", parameterTypes, arguments) //
                        .get(5, TimeUnit.SECONDS);
                assertEquals("metadata", response.getData());
                assertEquals("metadata", response.getOption("x-dubbo-test"));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientCallsMeteorOverTls() throws Exception {
        // TLS 由传输配置启用，服务仍使用同一个 Dubbo 协议实现。
        MetConfiguration configuration = this.secureConfiguration(true, "NONE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SSL 配置直接信任测试 CA，并按服务端要求提供客户端身份。
            String[] arguments = { "--command=client", "--port=" + port, "--scenario=echo", //
                    "--group=interop", "--version=1.0.0", "--tls=true", //
                    "--certificate=" + this.pem("client.pem"), //
                    "--private-key=" + this.pem("client-key.pem"), //
                    "--trust-certificate=" + this.pem("ca.pem") };
            try (DubboProcess peer = DubboProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Dubbo 中文 🙂", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo3OverTls() throws Exception {
        // 官方服务端通过 PEM 配置发布加密的原生 Dubbo 服务。
        String[] arguments = { "--command=server", "--port=0", "--group=interop", //
                "--version=1.0.0", "--tls=true", //
                "--certificate=" + this.pem("server.pem"), //
                "--private-key=" + this.pem("server-key.pem") };
        try (DubboProcess peer = DubboProcess.start(arguments)) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.secureConfiguration(false, null);
            try (MetContext client = configuration.buildContext()) {
                // 启动后通过普通订阅入口声明服务，安全性属于端点配置。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbos", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 真实 TLS 连接完成后仍验证完整业务响应。
                assertEquals("Dubbo 中文 🙂", remote.echo("Dubbo 中文 🙂"));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3ClientCallsMeteorOverMutualTls() throws Exception {
        // TLS 由传输配置启用，服务仍使用同一个 Dubbo 协议实现。
        MetConfiguration configuration = this.secureConfiguration(true, "REQUIRE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SSL 配置直接信任测试 CA，并按服务端要求提供客户端身份。
            String[] arguments = { "--command=client", "--port=" + port, "--scenario=echo", //
                    "--group=interop", "--version=1.0.0", "--tls=true", //
                    "--certificate=" + this.pem("client.pem"), //
                    "--private-key=" + this.pem("client-key.pem"), //
                    "--trust-certificate=" + this.pem("ca.pem") };
            try (DubboProcess peer = DubboProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Dubbo 中文 🙂", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo3OverMutualTls() throws Exception {
        // 官方服务端通过 PEM 配置发布加密的原生 Dubbo 服务。
        String[] arguments = { "--command=server", "--port=0", "--group=interop", //
                "--version=1.0.0", "--tls=true", //
                "--certificate=" + this.pem("server.pem"), //
                "--private-key=" + this.pem("server-key.pem"), //
                "--trust-certificate=" + this.pem("ca.pem") };
        try (DubboProcess peer = DubboProcess.start(arguments)) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.secureConfiguration(false, null);
            try (MetContext client = configuration.buildContext()) {
                // 启动后通过普通订阅入口声明服务，安全性属于端点配置。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbos", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 真实 TLS 连接完成后仍验证完整业务响应。
                assertEquals("Dubbo 中文 🙂", remote.echo("Dubbo 中文 🙂"));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientCallsMeteorOverTls() throws Exception {
        // TLS 由传输配置启用，服务仍使用同一个 Dubbo 协议实现。
        MetConfiguration configuration = this.secureConfiguration(true, "NONE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SSL 配置直接信任测试 CA，并按服务端要求提供客户端身份。
            String[] arguments = { "--command=client", "--port=" + port, "--scenario=echo", //
                    "--group=interop", "--version=1.0.0", "--tls=true", //
                    "--certificate=" + this.pem("client.pem"), //
                    "--private-key=" + this.pem("client-key.pem"), //
                    "--trust-certificate=" + this.pem("ca.pem") };
            try (DubboProcess peer = DubboProcess.startLegacy(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Dubbo 中文 🙂", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo2OverTls() throws Exception {
        // 官方服务端通过 PEM 配置发布加密的原生 Dubbo 服务。
        String[] arguments = { "--command=server", "--port=0", "--group=interop", //
                "--version=1.0.0", "--tls=true", //
                "--certificate=" + this.pem("server.pem"), //
                "--private-key=" + this.pem("server-key.pem") };
        try (DubboProcess peer = DubboProcess.startLegacy(arguments)) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.secureConfiguration(false, null);
            try (MetContext client = configuration.buildContext()) {
                // 启动后通过普通订阅入口声明服务，安全性属于端点配置。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbos", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 真实 TLS 连接完成后仍验证完整业务响应。
                assertEquals("Dubbo 中文 🙂", remote.echo("Dubbo 中文 🙂"));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2ClientCallsMeteorOverMutualTls() throws Exception {
        // TLS 由传输配置启用，服务仍使用同一个 Dubbo 协议实现。
        MetConfiguration configuration = this.secureConfiguration(true, "REQUIRE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 SSL 配置直接信任测试 CA，并按服务端要求提供客户端身份。
            String[] arguments = { "--command=client", "--port=" + port, "--scenario=echo", //
                    "--group=interop", "--version=1.0.0", "--tls=true", //
                    "--certificate=" + this.pem("client.pem"), //
                    "--private-key=" + this.pem("client-key.pem"), //
                    "--trust-certificate=" + this.pem("ca.pem") };
            try (DubboProcess peer = DubboProcess.startLegacy(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("Dubbo 中文 🙂", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsOfficialDubbo2OverMutualTls() throws Exception {
        // 官方服务端通过 PEM 配置发布加密的原生 Dubbo 服务。
        String[] arguments = { "--command=server", "--port=0", "--group=interop", //
                "--version=1.0.0", "--tls=true", //
                "--certificate=" + this.pem("server.pem"), //
                "--private-key=" + this.pem("server-key.pem"), //
                "--trust-certificate=" + this.pem("ca.pem") };
        try (DubboProcess peer = DubboProcess.startLegacy(arguments)) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.secureConfiguration(false, null);
            try (MetContext client = configuration.buildContext()) {
                // 启动后通过普通订阅入口声明服务，安全性属于端点配置。
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                        .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                        .serialize("Hessian2").register();
                InterAddress address = new InterAddress("dubbos", peer.host(), port, "default");
                DubboInteropService remote = client.getRsfClient(address).getRemote(service);

                // 真实 TLS 连接完成后仍验证完整业务响应。
                assertEquals("Dubbo 中文 🙂", remote.echo("Dubbo 中文 🙂"));
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3GenericClientCallsMeteorMultipleArguments() throws Exception {
        // Meteor 仍发布普通 Java 类型接口，不为泛化调用另设业务服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 独立官方进程通过 GenericService.$invoke 发送方法名、类型名和参数。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=generic")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("hello:3", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3GenericClientCallsMeteorPerson() throws Exception {
        // Meteor 仍发布普通 Java 类型接口，不为泛化调用另设业务服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 独立官方进程通过 GenericService.$invoke 发送方法名、类型名和参数。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=generic-person")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                JsonNode person = result.path("value");
                assertEquals("Ada", person.path("name").asText());
                assertEquals(42, person.path("age").asInt());
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2GenericClientCallsMeteorMultipleArguments() throws Exception {
        // Meteor 仍发布普通 Java 类型接口，不为泛化调用另设业务服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 独立官方进程通过 GenericService.$invoke 发送方法名、类型名和参数。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=generic")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                assertEquals("hello:3", result.path("value").asText());
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo2GenericClientCallsMeteorPerson() throws Exception {
        // Meteor 仍发布普通 Java 类型接口，不为泛化调用另设业务服务。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 独立官方进程通过 GenericService.$invoke 发送方法名、类型名和参数。
            try (DubboProcess peer = DubboProcess.startLegacy("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=generic-person")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                JsonNode person = result.path("value");
                assertEquals("Ada", person.path("name").asText());
                assertEquals(42, person.path("age").asInt());
            }
        }
    }

    @Test(timeout = 60000)
    public void officialDubbo3GenericClientCallsMeteorPeople() throws Exception {
        // 服务端正常发布 List<DTO> 方法，保持真实 Java 泛型声明。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").aliasName("dubbo", SERVICE).group("interop").version("1.0.0") //
                    .serialize("Hessian2").register();
            int port = server.getListenAddress("dubbo").getPort();

            // 官方 GenericService 发送 List<Map>，Meteor 按方法泛型将集合元素恢复为 DTO。
            try (DubboProcess peer = DubboProcess.start("--command=client", "--port=" + port, //
                    "--group=interop", "--version=1.0.0", "--scenario=generic-people")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.toString(), result.has("error"));
                JsonNode people = result.path("value");
                assertTrue(people.isArray());
                assertEquals(1, people.size());
                JsonNode person = people.get(0);
                assertEquals("Ada", person.path("name").asText());
                assertEquals(42, person.path("age").asInt());
            }
        }
    }

    private MetConfiguration secureConfiguration(boolean listen, String clientAuth) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo");
        configuration.setDefaultTimeout(5000);
        var connector = configuration.connector("tcp");
        connector.option("tlsEnabled", true) //
                .option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test");
        if (listen) {
            connector.bind("0.0.0.0", 0) //
                    .option("tlsKeyStore", "classpath:/certs/acceptance/server.p12") //
                    .option("tlsKeyStorePassword", "meteor-test") //
                    .option("tlsClientAuth", clientAuth);
        } else {
            connector.option("tlsKeyStore", "classpath:/certs/acceptance/client.p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
        }
        connector.protocol("dubbo").addressScheme("dubbos");
        return configuration;
    }

    private String pem(String filename) throws Exception {
        return Path.of(this.getClass().getResource("/certs/acceptance/" + filename).toURI()).toString();
    }

    private MetConfiguration configuration(boolean listen) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo");
        configuration.setDefaultTimeout(5000);
        var connector = configuration.connector("tcp");
        if (listen) {
            // 官方 Dubbo 会规范化 loopback，监听本机接口以保持原生对端可达。
            connector.bind("0.0.0.0", 0);
        }
        connector.protocol("dubbo");
        return configuration;
    }

    private boolean isRpcTimeout(Throwable failure) {
        return this.findCause(failure, MetTimeoutException.class) != null;
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }
}
