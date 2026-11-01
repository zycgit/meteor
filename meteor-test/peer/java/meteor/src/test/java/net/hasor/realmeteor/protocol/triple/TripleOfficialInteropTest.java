/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.triple.interop.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 官方 Dubbo 独立 JVM 与公开 Meteor bootstrap 的双向网络互通。 */
@NxnRpc(name = "triple")
public class TripleOfficialInteropTest {
    private static final String WRAPPED = "net.hasor.peer.protocol.triple.DubboWrappedService";
    private static final String PROTO   = "net.hasor.peer.protocol.triple.DubboProtoService";

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorWrappedEcho() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=wrapped", "--scenario=echo" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("Triple 中文 🙂", result.path("value").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialWrappedEcho() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                assertEquals("Triple 中文 🙂", remote.echo("Triple 中文 🙂"));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorWrappedMultipleArguments() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=wrapped", "--scenario=combine" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("hello:3", result.path("value").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialWrappedMultipleArguments() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                assertEquals("hello:3", remote.combine("hello", 3));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorWrappedStringOverload() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=wrapped", "--scenario=string-overload" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("string:hello", result.path("value").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialWrappedStringOverload() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                assertEquals("string:hello", remote.overloaded("hello"));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorWrappedPrimitiveOverload() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=wrapped", "--scenario=int-overload" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("int:7", result.path("value").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialWrappedPrimitiveOverload() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                assertEquals("int:7", remote.overloaded(7));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorWrappedNoArguments() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=wrapped", "--scenario=no-args" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("empty", result.path("value").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialWrappedNoArguments() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                assertEquals("empty", remote.noArgs());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorWrappedBinary() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=wrapped", "--scenario=binary" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("AAEC/w==", result.path("value").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialWrappedBinary() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                byte[] value = { 0, 1, 2, -1 };
                assertArrayEquals(value, remote.binary(value));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorWrappedPojo() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=wrapped", "--scenario=person" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("Ada", result.path("value").path("name").asText());
                assertEquals(42, result.path("value").path("age").asInt());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialWrappedPojo() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                TripleInteropPerson person = remote.person(new TripleInteropPerson("Ada", 41));
                assertEquals("Ada", person.getName());
                assertEquals(42, person.getAge());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorWrappedNull() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=wrapped", "--scenario=null" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertTrue(result.path("value").isNull(), result.toString());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialWrappedNull() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                assertNull(remote.nullable(null));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorWrappedVoid() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=wrapped", "--scenario=void" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertTrue(result.path("value").isNull(), result.toString());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialWrappedVoid() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                remote.nothing();
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorWrappedFault() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=wrapped", "--scenario=fault" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.path("error").asText().contains("boom"), result.toString());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialWrappedFault() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                Throwable failure = assertThrows(Throwable.class, () -> remote.fail("boom"));
                assertTrue(this.messages(failure).contains("boom"));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorWrappedGzip() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=wrapped", "--scenario=echo", "--gzip=true" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("Triple 中文 🙂", result.path("value").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialLegacyWrappedGzip() throws Exception {
        // 官方 3.2.20 原版服务端支持压缩首帧；3.3.6 的 Java 接口入口有对应解码缺陷。
        try (DubboTripleProcess peer = DubboTripleProcess.startLegacy("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, true);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                assertEquals("Triple 中文 🙂", remote.echo("Triple 中文 🙂"));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorProtobufEcho() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropProtoService.class, new TripleInteropProtoServiceImpl()) //
                    .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                    .bindResource("triple", this.proto()) //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=proto", "--scenario=echo" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("Triple 中文 🙂", result.path("value").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialProtobufEcho() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropProtoService> service = client.publisher() //
                        .rsfService(TripleInteropProtoService.class) //
                        .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                        .bindResource("triple", this.proto()) //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropProtoService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                assertEquals("Triple 中文 🙂", remote.echo("Triple 中文 🙂"));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorProtobufServerStream() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropProtoService.class, new TripleInteropProtoServiceImpl()) //
                    .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                    .bindResource("triple", this.proto()) //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=proto", "--scenario=server-stream" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals(List.of("0", "1", "2"), this.values(result.path("value")));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialProtobufServerStream() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropProtoService> service = client.publisher() //
                        .rsfService(TripleInteropProtoService.class) //
                        .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                        .bindResource("triple", this.proto()) //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropProtoService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                List<String> received = new ArrayList<>();
                MetCaller.consume(remote.count("3"), received::add).get(5, TimeUnit.SECONDS);
                assertEquals(List.of("0", "1", "2"), received);
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorProtobufClientStream() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropProtoService.class, new TripleInteropProtoServiceImpl()) //
                    .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                    .bindResource("triple", this.proto()) //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=proto", "--scenario=client-stream" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("alpha,beta,gamma", result.path("value").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialProtobufClientStream() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropProtoService> service = client.publisher() //
                        .rsfService(TripleInteropProtoService.class) //
                        .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                        .bindResource("triple", this.proto()) //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropProtoService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                List<String> messages = List.of("alpha", "beta", "gamma");
                assertEquals("alpha,beta,gamma", remote.collect(MetCaller.fromIterator(messages::iterator)));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorProtobufBidirectionalStream() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropProtoService.class, new TripleInteropProtoServiceImpl()) //
                    .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                    .bindResource("triple", this.proto()) //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=proto", "--scenario=bidi" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals(List.of("alpha", "beta", "gamma"), this.values(result.path("value")));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialProtobufBidirectionalStream() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropProtoService> service = client.publisher() //
                        .rsfService(TripleInteropProtoService.class) //
                        .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                        .bindResource("triple", this.proto()) //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropProtoService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                List<String> sent = List.of("alpha", "beta", "gamma");
                List<String> received = new ArrayList<>();
                var outgoing = MetCaller.fromIterator(sent::iterator);
                MetCaller.consume(remote.chat(outgoing), received::add).get(5, TimeUnit.SECONDS);
                assertEquals(sent, received);
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorProtobufFault() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropProtoService.class, new TripleInteropProtoServiceImpl()) //
                    .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                    .bindResource("triple", this.proto()) //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=proto", "--scenario=fault" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertTrue(result.path("error").asText().contains("boom"), result.toString());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialProtobufFault() throws Exception {
        // 独立官方进程通过真实 ServiceConfig 发布 Triple 服务。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropProtoService> service = client.publisher() //
                        .rsfService(TripleInteropProtoService.class) //
                        .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                        .bindResource("triple", this.proto()) //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropProtoService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                Throwable failure = assertThrows(Throwable.class, () -> remote.fail("boom"));
                assertTrue(this.messages(failure).contains("boom"));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientCallsMeteorProtobufGzip() throws Exception {
        // 启动 HTTP/2 端点后，发布普通 Java 接口及相应编码。
        MetConfiguration configuration = this.configuration(true, true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropProtoService.class, new TripleInteropProtoServiceImpl()) //
                    .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                    .bindResource("triple", this.proto()) //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 对端仅加载官方 Dubbo 类库，使用其真实 ReferenceConfig 发起调用。
            String[] arguments = { "client", "--port=" + port, "--mode=proto", "--scenario=echo", "--gzip=true" };
            try (DubboTripleProcess peer = DubboTripleProcess.start(arguments)) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("Triple 中文 🙂", result.path("value").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialLegacyProtobufGzip() throws Exception {
        // 官方 3.2.20 原版服务端支持压缩首帧；3.3.6 的 Java 接口入口有对应解码缺陷。
        try (DubboTripleProcess peer = DubboTripleProcess.startLegacy("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, true);
            try (MetContext client = configuration.buildContext()) {
                // Meteor 侧仍从公开发布与订阅入口绑定业务接口。
                client.start();
                MetBindInfo<TripleInteropProtoService> service = client.publisher() //
                        .rsfService(TripleInteropProtoService.class) //
                        .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                        .bindResource("triple", this.proto()) //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropProtoService remote = client.getRsfClient(address).getRemote(service);

                // 通过正式客户端入口调用，验证本场景的对端结果。
                assertEquals("Triple 中文 🙂", remote.echo("Triple 中文 🙂"));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientReceivesMeteorWrappedMetadata() throws Exception {
        // 双方通过公开请求和响应元信息传播同一文本字段。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().bindFilter("official-response-metadata", (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addTrailer("x-triple-test", incoming.getHeader("x-triple-test").get(0));
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 官方客户端独立读取服务端字段，避免双方共用元信息解码逻辑。
            try (DubboTripleProcess peer = DubboTripleProcess.start("client", "--port=" + port, //
                    "--mode=wrapped", "--scenario=metadata")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("metadata", result.path("metadata").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorReceivesOfficialWrappedMetadata() throws Exception {
        // 官方服务从 RpcContext 接收并回送元信息，Meteor 侧从响应读取。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                client.publisher().bindFilter("official-request-metadata", (request, response, chain) -> {
                    GrpcMetadata.from(request).addHeader("x-triple-test", "metadata");
                    chain.doFilter(request, response);
                });
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");

                // 使用公开异步调用入口保留完整响应，验证回送字段。
                Class<?>[] types = { String.class };
                Object[] arguments = { "metadata" };
                MetResponse response = client.getRsfClient(address) //
                        .asyncInvoke(service, "metadata", types, arguments) //
                        .get(5, TimeUnit.SECONDS);
                assertEquals(List.of("metadata"), GrpcMetadata.from(response).getTrailer("x-triple-test"));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientTimesOutMeteorWrappedCall() throws Exception {
        // 服务端业务有界延迟，调用期限由官方客户端独立执行。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2") //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 官方 ReferenceConfig 为该调用设置 100 毫秒期限。
            try (DubboTripleProcess peer = DubboTripleProcess.start("client", "--port=" + port, //
                    "--mode=wrapped", "--scenario=timeout")) {
                JsonNode result = peer.awaitResult();
                String error = result.path("error").asText().toLowerCase(Locale.ROOT);
                assertTrue(error.contains("timeout"), result.toString());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorTimesOutOfficialWrappedCall() throws Exception {
        // 官方提供者继续处理业务，Meteor 在自己的调用期限到达时失败。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2").timeout(100) //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");

                // 外层等待大于 RPC 期限，只观察 Caller 或远端协议给出的期限结果。
                Class<?>[] types = { String.class };
                Object[] arguments = { "wait" };
                var pending = client.getRsfClient(address).asyncInvoke(service, "slow", types, arguments);
                Throwable failure = assertThrows(Throwable.class, () -> pending.get(5, TimeUnit.SECONDS));
                assertTrue(this.isDeadlineFailure(failure), this.messages(failure));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientReceivesMeteorProtobufMetadata() throws Exception {
        // 双方通过公开请求和响应元信息传播同一文本字段。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().bindFilter("official-response-metadata", (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addTrailer("x-triple-test", incoming.getHeader("x-triple-test").get(0));
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(TripleInteropProtoService.class, new TripleInteropProtoServiceImpl()) //
                    .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                    .bindResource("triple", this.proto()) //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 官方客户端独立读取服务端字段，避免双方共用元信息解码逻辑。
            try (DubboTripleProcess peer = DubboTripleProcess.start("client", "--port=" + port, //
                    "--mode=proto", "--scenario=metadata")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertEquals("metadata", result.path("metadata").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorReceivesOfficialProtobufMetadata() throws Exception {
        // 官方服务从 RpcContext 接收并回送元信息，Meteor 侧从响应读取。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                client.publisher().bindFilter("official-request-metadata", (request, response, chain) -> {
                    GrpcMetadata.from(request).addHeader("x-triple-test", "metadata");
                    chain.doFilter(request, response);
                });
                MetBindInfo<TripleInteropProtoService> service = client.publisher() //
                        .rsfService(TripleInteropProtoService.class) //
                        .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                        .bindResource("triple", this.proto()) //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");

                // 使用公开异步调用入口保留完整响应，验证回送字段。
                Class<?>[] types = { String.class };
                Object[] arguments = { "metadata" };
                MetResponse response = client.getRsfClient(address) //
                        .asyncInvoke(service, "metadata", types, arguments) //
                        .get(5, TimeUnit.SECONDS);
                assertEquals(List.of("metadata"), GrpcMetadata.from(response).getTrailer("x-triple-test"));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialClientTimesOutMeteorProtobufCall() throws Exception {
        // 服务端业务有界延迟，调用期限由官方客户端独立执行。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropProtoService.class, new TripleInteropProtoServiceImpl()) //
                    .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                    .bindResource("triple", this.proto()) //
                    .register();
            int port = server.getListenAddress("triple").getPort();

            // 官方 ReferenceConfig 为该调用设置 100 毫秒期限。
            try (DubboTripleProcess peer = DubboTripleProcess.start("client", "--port=" + port, //
                    "--mode=proto", "--scenario=timeout")) {
                JsonNode result = peer.awaitResult();
                String error = result.path("error").asText().toLowerCase(Locale.ROOT);
                assertTrue(error.contains("timeout"), result.toString());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorTimesOutOfficialProtobufCall() throws Exception {
        // 官方提供者继续处理业务，Meteor 在自己的调用期限到达时失败。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<TripleInteropProtoService> service = client.publisher() //
                        .rsfService(TripleInteropProtoService.class) //
                        .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf").timeout(100) //
                        .bindResource("triple", this.proto()) //
                        .register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");

                // 外层等待大于 RPC 期限，只观察 Caller 或远端协议给出的期限结果。
                Class<?>[] types = { String.class };
                Object[] arguments = { "wait" };
                var pending = client.getRsfClient(address).asyncInvoke(service, "slow", types, arguments);
                Throwable failure = assertThrows(Throwable.class, () -> pending.get(5, TimeUnit.SECONDS));
                assertTrue(this.isDeadlineFailure(failure), this.messages(failure));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialPlainJsonHttp1() throws Exception {
        // 普通 HTTP JSON 与官方 Dubbo 的 HTTP RPC 入口互通，无需 Protobuf 定义。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
            configuration.connector("http").option("httpVersion", 1) //
                    .protocol("triple").option("httpMode", "json");
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Json").register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 业务代理发起多参数调用，真实传输使用指定的 HTTP 版本。
                assertEquals("hello:3", remote.combine("hello", 3));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCallsOfficialPlainJsonHttp2() throws Exception {
        // 普通 HTTP JSON 与官方 Dubbo 的 HTTP RPC 入口互通，无需 Protobuf 定义。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
            configuration.connector("http").option("httpVersion", 2) //
                    .protocol("triple").option("httpMode", "json");
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Json").register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);

                // 业务代理发起多参数调用，真实传输使用指定的 HTTP 版本。
                assertEquals("hello:3", remote.combine("hello", 3));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialCancellationKeepsMeteorConnectionUsable() throws Exception {
        // 发布双向流服务，官方客户端负责取消其中一条正在收发的 RPC。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropProtoService.class, new TripleInteropProtoServiceImpl()) //
                    .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                    .bindResource("triple", this.proto()).register();
            int port = server.getListenAddress("triple").getPort();

            // 官方取消 API 结束一条流后，同一业务代理仍能完成普通调用。
            try (DubboTripleProcess peer = DubboTripleProcess.start("client", "--port=" + port, //
                    "--mode=proto", "--scenario=cancel")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                assertTrue(result.path("cancelled").asBoolean());
                assertEquals("alive", result.path("value").asText());
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorCancellationKeepsOfficialConnectionUsable() throws Exception {
        // 官方服务保持双向流开启，Meteor 在收到首条消息后主动取消。
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<TripleInteropProtoService> service = client.publisher() //
                        .rsfService(TripleInteropProtoService.class) //
                        .protocol("triple").aliasName("triple", PROTO).serialize("Protobuf") //
                        .bindResource("triple", this.proto()).register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropProtoService remote = client.getRsfClient(address).getRemote(service);
                CountDownLatch sourceCancelled = new CountDownLatch(1);
                Flow.Publisher<String> source = this.openSource(sourceCancelled);
                CountDownLatch first = new CountDownLatch(1);

                // 消费者取消必须传播至请求源，并且不会关闭共用的物理连接。
                var consuming = MetCaller.consume(remote.chat(source), value -> first.countDown());
                assertTrue(first.await(5, TimeUnit.SECONDS));
                consuming.cancel();
                assertTrue(sourceCancelled.await(5, TimeUnit.SECONDS));
                assertEquals("alive", remote.echo("alive"));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialConcurrentCallsKeepTheirMeteorResults() throws Exception {
        // 官方客户端在同一端点上并发提交独立 RPC。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                    .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2").register();
            int port = server.getListenAddress("triple").getPort();

            // 各结果按提交顺序输出，验证 HTTP/2 多调用不会互相串包。
            try (DubboTripleProcess peer = DubboTripleProcess.start("client", "--port=" + port, //
                    "--mode=wrapped", "--scenario=concurrent")) {
                JsonNode result = peer.awaitResult();
                assertFalse(result.has("error"), result.toString());
                List<String> expected = new ArrayList<>();
                for (int index = 0; index < 20; index++) {
                    expected.add("call-" + index);
                }
                assertEquals(expected, this.values(result.path("value")));
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConcurrentCallsKeepTheirOfficialResults() throws Exception {
        // 业务线程池由本场景创建并关闭，不让官方对端持有 Meteor 资源。
        var calls = Executors.newFixedThreadPool(5);
        try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = this.configuration(false, false);
            try (MetContext client = configuration.buildContext()) {
                client.start();
                MetBindInfo<TripleInteropWrappedService> service = client.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .protocol("triple").aliasName("triple", WRAPPED).serialize("Hessian2").register();
                InterAddress address = new InterAddress("triple", peer.host(), port, "default");
                TripleInteropWrappedService remote = client.getRsfClient(address).getRemote(service);
                List<Future<String>> results = new ArrayList<>();

                // 每个并发请求保留自己的参数，逐个核对对应的业务结果。
                for (int index = 0; index < 20; index++) {
                    String argument = "call-" + index;
                    results.add(calls.submit(() -> remote.echo(argument)));
                }
                for (int index = 0; index < results.size(); index++) {
                    assertEquals("call-" + index, results.get(index).get(5, TimeUnit.SECONDS));
                }
            }
        } finally {
            calls.shutdownNow();
            assertTrue(calls.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Flow.Publisher<String> openSource(CountDownLatch cancelled) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean sent = new AtomicBoolean();

            @Override
            public void request(long count) {
                // 只在首次需求时发送一条消息，保留请求方向等待显式取消。
                if (count > 0 && this.sent.compareAndSet(false, true)) {
                    subscriber.onNext("alpha");
                }
            }

            @Override
            public void cancel() {
                cancelled.countDown();
            }
        });
    }

    private MetConfiguration configuration(boolean listen, boolean gzip) {
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        var connector = configuration.connector("http").option("httpVersion", 2);
        if (listen) {
            // Dubbo 会把 loopback 目标规范化为本机接口，监听全部本地地址保持可达。
            connector.bind("0.0.0.0", 0);
        }
        var protocol = connector.protocol("triple").option("streamBody", true);
        if (gzip) {
            protocol.option("compression", "gzip");
        }
        return configuration;
    }

    private GrpcProtoResource proto() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-interop.proto"));
    }

    private List<String> values(JsonNode input) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : input) {
            values.add(value.asText());
        }
        return values;
    }

    private boolean isDeadlineFailure(Throwable failure) {
        // 本地 Caller 期限与远端 DEADLINE_EXCEEDED 都表示本次调用已超时。
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof MetTimeoutException) {
                return true;
            }
            if (current instanceof GrpcStatusException status && status.getCode() == 4) {
                return true;
            }
        }
        return false;
    }

    private String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        while (failure != null) {
            result.append(failure.getClass().getName()).append(": ").append(failure.getMessage()).append('\n');
            failure = failure.getCause();
        }
        return result.toString();
    }
}
