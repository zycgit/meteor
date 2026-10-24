/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.thrift.annotated.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftIdlResource;
import org.junit.Test;
import static org.junit.Assert.*;

/** 注解方法、声明异常与单向消息分别经官方对端验证。 */
public class ThriftAnnotatedContractScenarioTest {
    @Test(timeout = 30000)
    public void providerMapsMethodAliasForTheOfficialClient() throws Exception {
        // 普通 Java 服务经发布入口编译注解，官方 Client 只执行 extended 场景。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(AnnotatedInteropService.class, new AnnotatedInteropServiceImpl()) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();

            // 使用真实端口与隔离 JVM，不通过本地方法或生成桥接对象。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--service-name=ExtendedInteropService", "--port=" + port, //
                    "--scenario=extended", "--value=alias-value")) {
                peer.requireResult("alias-value");
            }
        }
    }

    @Test(timeout = 30000)
    public void consumerMapsMethodAliasFromTheOfficialServer() throws Exception {
        // 官方服务端保留原生生成 Processor，Meteor 只订阅普通注解接口。
        MetConfiguration configuration = this.configuration(false, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--service-name=ExtendedInteropService", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<AnnotatedInteropService> service = client.publisher() //
                    .rsfService(AnnotatedInteropService.class) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();
            InterAddress address = new InterAddress("thrift", peer.host(), port, "default");
            AnnotatedInteropService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);

            // 只验证 extended 的注解契约行为。
            assertEquals("alias-value", remote.renamed("alias-value"));
        }
    }

    @Test(timeout = 30000)
    public void providerMapsArgumentNumbersForTheOfficialClient() throws Exception {
        // 普通 Java 服务经发布入口编译注解，官方 Client 只执行 add 场景。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(AnnotatedInteropService.class, new AnnotatedInteropServiceImpl()) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();

            // 使用真实端口与隔离 JVM，不通过本地方法或生成桥接对象。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--service-name=ExtendedInteropService", "--port=" + port, //
                    "--scenario=add", "--value=unused")) {
                peer.requireResult("42");
            }
        }
    }

    @Test(timeout = 30000)
    public void consumerMapsArgumentNumbersFromTheOfficialServer() throws Exception {
        // 官方服务端保留原生生成 Processor，Meteor 只订阅普通注解接口。
        MetConfiguration configuration = this.configuration(false, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--service-name=ExtendedInteropService", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<AnnotatedInteropService> service = client.publisher() //
                    .rsfService(AnnotatedInteropService.class) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();
            InterAddress address = new InterAddress("thrift", peer.host(), port, "default");
            AnnotatedInteropService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);

            // 只验证 add 的注解契约行为。
            assertEquals(42, remote.sum(19, 23));
        }
    }

    @Test(timeout = 30000)
    public void providerMapsDeclaredExceptionForTheOfficialClient() throws Exception {
        // 普通 Java 服务经发布入口编译注解，官方 Client 只执行 declared 场景。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(AnnotatedInteropService.class, new AnnotatedInteropServiceImpl()) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();

            // 使用真实端口与隔离 JVM，不通过本地方法或生成桥接对象。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--service-name=ExtendedInteropService", "--port=" + port, //
                    "--scenario=declared", "--value=rejected")) {
                peer.requireResult("declared:409:rejected");
            }
        }
    }

    @Test(timeout = 30000)
    public void consumerMapsDeclaredExceptionFromTheOfficialServer() throws Exception {
        // 官方服务端保留原生生成 Processor，Meteor 只订阅普通注解接口。
        MetConfiguration configuration = this.configuration(false, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--service-name=ExtendedInteropService", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<AnnotatedInteropService> service = client.publisher() //
                    .rsfService(AnnotatedInteropService.class) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();
            InterAddress address = new InterAddress("thrift", peer.host(), port, "default");
            AnnotatedInteropService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);

            // 只验证 declared 的注解契约行为。
            AnnotatedFailure failure = assertThrows(AnnotatedFailure.class, () -> remote.fail("rejected"));
            assertEquals(409, failure.getCode());
            assertEquals("rejected", failure.getReason());
        }
    }

    @Test(timeout = 30000)
    public void providerMapsOptionalPresenceForTheOfficialClient() throws Exception {
        // 普通 Java 服务经发布入口编译注解，官方 Client 只执行 optional 场景。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(AnnotatedInteropService.class, new AnnotatedInteropServiceImpl()) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();

            // 使用真实端口与隔离 JVM，不通过本地方法或生成桥接对象。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--service-name=ExtendedInteropService", "--port=" + port, //
                    "--scenario=optional", "--value=unused")) {
                peer.requireResult("optional-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void consumerMapsOptionalPresenceFromTheOfficialServer() throws Exception {
        // 官方服务端保留原生生成 Processor，Meteor 只订阅普通注解接口。
        MetConfiguration configuration = this.configuration(false, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--service-name=ExtendedInteropService", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<AnnotatedInteropService> service = client.publisher() //
                    .rsfService(AnnotatedInteropService.class) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();
            InterAddress address = new InterAddress("thrift", peer.host(), port, "default");
            AnnotatedInteropService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);

            // 只验证 optional 的注解契约行为。
            AnnotatedSample result = remote.mirror(new AnnotatedSample());
            assertNull(result.getNote());
            assertNull(result.getOptionalCount());
            assertNull(result.getChoice());
            assertNull(result.getRequestId());
        }
    }

    @Test(timeout = 30000)
    public void providerMapsDefaultValuesForTheOfficialClient() throws Exception {
        // 普通 Java 服务经发布入口编译注解，官方 Client 只执行 defaults 场景。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(AnnotatedInteropService.class, new AnnotatedInteropServiceImpl()) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();

            // 使用真实端口与隔离 JVM，不通过本地方法或生成桥接对象。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--service-name=ExtendedInteropService", "--port=" + port, //
                    "--scenario=defaults", "--value=unused")) {
                peer.requireResult("defaults-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void consumerMapsDefaultValuesFromTheOfficialServer() throws Exception {
        // 官方服务端保留原生生成 Processor，Meteor 只订阅普通注解接口。
        MetConfiguration configuration = this.configuration(false, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--service-name=ExtendedInteropService", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<AnnotatedInteropService> service = client.publisher() //
                    .rsfService(AnnotatedInteropService.class) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();
            InterAddress address = new InterAddress("thrift", peer.host(), port, "default");
            AnnotatedInteropService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);

            // 只验证 defaults 的注解契约行为。
            AnnotatedSample result = remote.defaults();
            assertEquals(17, result.getDefaultCount());
            assertEquals(AnnotatedShade.BLUE, result.getShade());
        }
    }

    @Test(timeout = 30000)
    public void providerMapsUnionBranchForTheOfficialClient() throws Exception {
        // 普通 Java 服务经发布入口编译注解，官方 Client 只执行 union 场景。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(AnnotatedInteropService.class, new AnnotatedInteropServiceImpl()) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();

            // 使用真实端口与隔离 JVM，不通过本地方法或生成桥接对象。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--service-name=ExtendedInteropService", "--port=" + port, //
                    "--scenario=union", "--value=unused")) {
                peer.requireResult("union-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void consumerMapsUnionBranchFromTheOfficialServer() throws Exception {
        // 官方服务端保留原生生成 Processor，Meteor 只订阅普通注解接口。
        MetConfiguration configuration = this.configuration(false, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--service-name=ExtendedInteropService", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<AnnotatedInteropService> service = client.publisher() //
                    .rsfService(AnnotatedInteropService.class) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();
            InterAddress address = new InterAddress("thrift", peer.host(), port, "default");
            AnnotatedInteropService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);

            // 只验证 union 的注解契约行为。
            AnnotatedChoice source = new AnnotatedChoice();
            source.setCount(Long.MAX_VALUE);
            AnnotatedChoice result = remote.echoUnion(source);
            assertEquals(source.getCount(), result.getCount());
            assertNull(result.getText());
            assertNull(result.getPayload());
        }
    }

    @Test(timeout = 30000)
    public void providerMapsOrdinaryVoidForTheOfficialClient() throws Exception {
        // 普通 Java 服务经发布入口编译注解，官方 Client 只执行 void 场景。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(AnnotatedInteropService.class, new AnnotatedInteropServiceImpl()) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();

            // 使用真实端口与隔离 JVM，不通过本地方法或生成桥接对象。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--service-name=ExtendedInteropService", "--port=" + port, //
                    "--scenario=void", "--value=unused")) {
                peer.requireResult("void-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void consumerMapsOrdinaryVoidFromTheOfficialServer() throws Exception {
        // 官方服务端保留原生生成 Processor，Meteor 只订阅普通注解接口。
        MetConfiguration configuration = this.configuration(false, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--service-name=ExtendedInteropService", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<AnnotatedInteropService> service = client.publisher() //
                    .rsfService(AnnotatedInteropService.class) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();
            InterAddress address = new InterAddress("thrift", peer.host(), port, "default");
            AnnotatedInteropService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);

            // 只验证 void 的注解契约行为。
            remote.ping();
        }
    }

    @Test(timeout = 30000)
    public void providerMapsOnewayForTheOfficialClient() throws Exception {
        // 普通 Java 服务经发布入口编译注解，官方 Client 只执行 oneway 场景。
        MetConfiguration configuration = this.configuration(true, false);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(AnnotatedInteropService.class, new AnnotatedInteropServiceImpl()) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();

            // 使用真实端口与隔离 JVM，不通过本地方法或生成桥接对象。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--service-name=ExtendedInteropService", "--port=" + port, //
                    "--scenario=oneway", "--value=event")) {
                peer.requireResult("oneway-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void consumerMapsOnewayFromTheOfficialServer() throws Exception {
        // 官方服务端保留原生生成 Processor，Meteor 只订阅普通注解接口。
        MetConfiguration configuration = this.configuration(false, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--service-name=ExtendedInteropService", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<AnnotatedInteropService> service = client.publisher() //
                    .rsfService(AnnotatedInteropService.class) //
                    .aliasName("thrift", "ExtendedInteropService") //
                    .bindResource("thrift", this.contract()).register();
            InterAddress address = new InterAddress("thrift", peer.host(), port, "default");
            AnnotatedInteropService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);

            // 只验证 oneway 的注解契约行为。
            int before = remote.notificationCount();
            remote.notify("event");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int count = remote.notificationCount();
            while (count == before && System.nanoTime() < deadline) {
                count = remote.notificationCount();
            }
            assertEquals(before + 1, count);
        }
    }

    @Test(timeout = 30000)
    public void providerMapsTheHttpMethodAliasForTheOfficialClient() throws Exception {
        // HTTP 使用相同的方法定义，Java renamed 对应官方 extended。
        MetConfiguration configuration = this.configuration(true, true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(AnnotatedInteropService.class, new AnnotatedInteropServiceImpl()) //
                    .aliasName("thrift-http", "ExtendedInteropService") //
                    .bindResource("thrift-http", this.contract()).register();

            // 官方 THttpClient 直接访问协议挂载路径。
            int port = server.getListenAddress("thrift-http").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=http", "--protocol=compact", "--path=/thrift", //
                    "--port=" + port, "--scenario=extended", "--value=http-alias")) {
                peer.requireResult("http-alias");
            }
        }
    }

    @Test(timeout = 30000)
    public void consumerCorrelatesTheHttpReplyUsingTheWireMethodName() throws Exception {
        // 响应方法名属于线协议，不能用 Java 别名进行关联。
        MetConfiguration configuration = this.configuration(false, true);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=http", "--protocol=binary", "--path=/thrift", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<AnnotatedInteropService> service = client.publisher() //
                    .rsfService(AnnotatedInteropService.class) //
                    .aliasName("thrift-http", "ExtendedInteropService") //
                    .bindResource("thrift-http", this.contract()).register();
            InterAddress address = new InterAddress("thrift-http", peer.host(), port, "default");
            AnnotatedInteropService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);

            // 官方响应 extended 正确完成 Java renamed 调用。
            assertEquals("http-alias", remote.renamed("http-alias"));
        }
    }

    private ThriftIdlResource contract() throws Exception {
        Path source = Path.of("src/test/thrift/interop.thrift");
        return new ThriftIdlResource(source.toUri().toURL()).selectService("ExtendedInteropService");
    }

    private MetConfiguration configuration(boolean listen, boolean http) {
        String protocol = http ? "thrift-http" : "thrift";
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(protocol);
        configuration.setDefaultTimeout(10000);
        MetConfiguration.ConnectorSettings connector = configuration.connector(http ? "http" : "tcp");
        if (listen) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        MetConfiguration.ProtocolSettings settings = connector.protocol(protocol);
        if (http) {
            settings.contextPath("/thrift");
        }
        return configuration;
    }
}
