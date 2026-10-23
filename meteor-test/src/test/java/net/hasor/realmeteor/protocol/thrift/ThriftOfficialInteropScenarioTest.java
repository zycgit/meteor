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
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.Choice;
import net.hasor.example.protocol.thrift.generated.DeclaredFailure;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.example.protocol.thrift.generated.Sample;
import net.hasor.example.protocol.thrift.generated.Shade;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.apache.thrift.TApplicationException;
import org.junit.Test;
import static org.junit.Assert.*;

/** 官方 Client、Processor 在隔离 JVM 中经真实网络与 Meteor 双向互通。 */
public class ThriftOfficialInteropScenarioTest {
    @Test(timeout = 30000)
    public void officialBinaryRawTcpCallsMeteor() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", false, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=false", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=echo", "--value=official → Meteor 中文🙂")) {
                peer.requireResult("official → Meteor 中文🙂");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryRawTcpCallsOfficial() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", false, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=false", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 通过公开客户端执行当前场景，并断言官方对端返回的结果。
            String value = "Meteor → official 中文🙂";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedTcpCallsMeteor() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=echo", "--value=official → Meteor 中文🙂")) {
                peer.requireResult("official → Meteor 中文🙂");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedTcpCallsOfficial() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 通过公开客户端执行当前场景，并断言官方对端返回的结果。
            String value = "Meteor → official 中文🙂";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryHttpCallsMeteor() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 http 传输。
        MetConfiguration configuration = this.incomingHttp("binary", false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift-http", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift-http").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=http", "--protocol=binary", "--framed=false", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=echo", "--value=official → Meteor 中文🙂")) {
                peer.requireResult("official → Meteor 中文🙂");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryHttpCallsOfficial() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 http。
        MetConfiguration configuration = this.outgoingHttp("binary", false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=http", "--protocol=binary", "--framed=false", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift-http", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift-http", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 通过公开客户端执行当前场景，并断言官方对端返回的结果。
            String value = "Meteor → official 中文🙂";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void officialCompactRawTcpCallsMeteor() throws Exception {
        // Meteor 公开监听器承载本场景的 compact 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("compact", false, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=compact", "--framed=false", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=echo", "--value=official → Meteor 中文🙂")) {
                peer.requireResult("official → Meteor 中文🙂");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorCompactRawTcpCallsOfficial() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 compact 与 tcp。
        MetConfiguration configuration = this.outgoing("compact", false, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=compact", "--framed=false", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 通过公开客户端执行当前场景，并断言官方对端返回的结果。
            String value = "Meteor → official 中文🙂";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void officialCompactFramedTcpCallsMeteor() throws Exception {
        // Meteor 公开监听器承载本场景的 compact 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("compact", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=compact", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=echo", "--value=official → Meteor 中文🙂")) {
                peer.requireResult("official → Meteor 中文🙂");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorCompactFramedTcpCallsOfficial() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 compact 与 tcp。
        MetConfiguration configuration = this.outgoing("compact", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=compact", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 通过公开客户端执行当前场景，并断言官方对端返回的结果。
            String value = "Meteor → official 中文🙂";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void officialCompactHttpCallsMeteor() throws Exception {
        // Meteor 公开监听器承载本场景的 compact 编码与 http 传输。
        MetConfiguration configuration = this.incomingHttp("compact", false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift-http", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift-http").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=http", "--protocol=compact", "--framed=false", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=echo", "--value=official → Meteor 中文🙂")) {
                peer.requireResult("official → Meteor 中文🙂");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorCompactHttpCallsOfficial() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 compact 与 http。
        MetConfiguration configuration = this.outgoingHttp("compact", false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=http", "--protocol=compact", "--framed=false", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift-http", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift-http", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 通过公开客户端执行当前场景，并断言官方对端返回的结果。
            String value = "Meteor → official 中文🙂";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void officialJsonRawTcpCallsMeteor() throws Exception {
        // Meteor 公开监听器承载本场景的 json 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("json", false, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=json", "--framed=false", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=echo", "--value=official → Meteor 中文🙂")) {
                peer.requireResult("official → Meteor 中文🙂");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorJsonRawTcpCallsOfficial() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 json 与 tcp。
        MetConfiguration configuration = this.outgoing("json", false, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=json", "--framed=false", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 通过公开客户端执行当前场景，并断言官方对端返回的结果。
            String value = "Meteor → official 中文🙂";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void officialJsonFramedTcpCallsMeteor() throws Exception {
        // Meteor 公开监听器承载本场景的 json 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("json", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=json", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=echo", "--value=official → Meteor 中文🙂")) {
                peer.requireResult("official → Meteor 中文🙂");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorJsonFramedTcpCallsOfficial() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 json 与 tcp。
        MetConfiguration configuration = this.outgoing("json", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=json", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 通过公开客户端执行当前场景，并断言官方对端返回的结果。
            String value = "Meteor → official 中文🙂";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void officialJsonHttpCallsMeteor() throws Exception {
        // Meteor 公开监听器承载本场景的 json 编码与 http 传输。
        MetConfiguration configuration = this.incomingHttp("json", false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift-http", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift-http").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=http", "--protocol=json", "--framed=false", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=echo", "--value=official → Meteor 中文🙂")) {
                peer.requireResult("official → Meteor 中文🙂");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorJsonHttpCallsOfficial() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 json 与 http。
        MetConfiguration configuration = this.outgoingHttp("json", false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=http", "--protocol=json", "--framed=false", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift-http", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift-http", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 通过公开客户端执行当前场景，并断言官方对端返回的结果。
            String value = "Meteor → official 中文🙂";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorArgumentFieldIds() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=add", "--value=payload")) {
                peer.requireResult("42");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialArgumentFieldIds() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // IDL 参数字段 ID 与声明顺序不同时仍按原方法参数映射。
            assertEquals(42, remote.add(19, 23));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorTypedDto() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=dto", "--value=payload")) {
                peer.requireResult("dto-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialTypedDto() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 有类型 DTO 保留标量、嵌套容器、有值 optional、union 和 UUID。
            Sample expected = this.sample();
            assertEquals(expected, remote.mirror(expected));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorUnsetOptionalFields() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=optional", "--value=payload")) {
                peer.requireResult("optional-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialUnsetOptionalFields() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 未设置的 optional 字段在往返后保持未设置。
            Sample actual = remote.mirror(new Sample());
            assertFalse(actual.isSetNote());
            assertFalse(actual.isSetOptionalCount());
            assertFalse(actual.isSetChoice());
            assertFalse(actual.isSetRequestId());
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorIdlDefaults() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=defaults", "--value=payload")) {
                peer.requireResult("defaults-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialIdlDefaults() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 官方生成类的整数和枚举默认值传回普通 Java 接口。
            Sample actual = remote.defaults();
            assertEquals(17, actual.getDefaultCount());
            assertEquals(Shade.BLUE, actual.getShade());
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorList() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=list", "--value=payload")) {
                peer.requireResult("list-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialList() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 列表保持 Unicode、空字符串与元素顺序。
            List<String> expected = Arrays.asList("alpha", "汉字", "");
            assertEquals(expected, remote.echoList(expected));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorMap() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=map", "--value=payload")) {
                peer.requireResult("map-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialMap() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 映射保留字符串键与正负整数值。
            Map<String, Integer> expected = new LinkedHashMap<>();
            expected.put("alpha", 17);
            expected.put("汉字", -3);
            assertEquals(expected, remote.echoMap(expected));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorSet() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=set", "--value=payload")) {
                peer.requireResult("set-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialSet() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 集合保留长整数边界值与成员语义。
            Set<Long> expected = new LinkedHashSet<>(Arrays.asList(7L, -9L, Long.MAX_VALUE));
            assertEquals(expected, remote.echoSet(expected));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorBinary() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=binary", "--value=payload")) {
                peer.requireResult("binary-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialBinary() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 二进制数据保留零字节、负字节与高位字节。
            ByteBuffer expected = ByteBuffer.wrap(new byte[] { 0, 1, 2, 127, -128, -1 });
            assertEquals(expected, remote.echoBinary(expected));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorEnum() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=enum", "--value=payload")) {
                peer.requireResult("enum-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialEnum() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 枚举以 IDL 数值在官方 SDK 和业务接口间往返。
            assertEquals(Shade.RED, remote.echoEnum(Shade.RED));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorUnion() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=union", "--value=payload")) {
                peer.requireResult("union-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialUnion() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // union 保留已选分支及长整数边界值。
            Choice expected = Choice.count(Long.MAX_VALUE);
            assertEquals(expected, remote.echoUnion(expected));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorUuid() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=uuid", "--value=payload")) {
                peer.requireResult("uuid-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialUuid() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // UUID 使用 Thrift 原生 UUID 字段往返。
            UUID expected = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
            assertEquals(expected, remote.echoUuid(expected));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorVoid() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=void", "--value=payload")) {
                peer.requireResult("void-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialVoid() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 普通 void 方法收到官方成功回应后完成调用。
            remote.ping();
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorOneway() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=oneway", "--value=payload")) {
                peer.requireResult("oneway-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialOneway() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // oneway 调用无需 RPC 回应，以官方端可观察计数证明通知实际到达。
            int before = remote.notificationCount();
            remote.notify("notification");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int observed = remote.notificationCount();
            while (observed <= before && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
                observed = remote.notificationCount();
            }
            assertEquals(before + 1, observed);
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorDeclaredException() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=declared", "--value=declared-detail")) {
                peer.requireResult("declared:409:declared-detail");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialDeclaredException() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // IDL 声明异常直接保持生成的异常类型及业务字段。
            DeclaredFailure failure = assertThrows(DeclaredFailure.class, () -> remote.fail("declared-detail"));
            assertEquals(409, failure.getCode());
            assertEquals("declared-detail", failure.getReason());
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorUnexpectedException() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=unexpected", "--value=unexpected-detail")) {
                peer.requireResult("application:6");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialUnexpectedException() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 未声明异常作为 Thrift INTERNAL_ERROR 到达调用方。
            TApplicationException failure = assertThrows(TApplicationException.class, //
                    () -> remote.crash("unexpected-detail"));
            assertEquals(TApplicationException.INTERNAL_ERROR, failure.getType());
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedCallsMeteorExtendedServiceMethod() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=extended", "--value=extended-value")) {
                peer.requireResult("extended-value");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryFramedCallsOfficialExtendedServiceMethod() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=false", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 扩展服务的方法与基类方法通过同一绑定发布。
            assertEquals("extended-value", remote.extended("extended-value"));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryMultiplexedCallsMeteor() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, true);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=true", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=echo", "--value=official → Meteor 中文🙂")) {
                peer.requireResult("official → Meteor 中文🙂");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryMultiplexedCallsOfficial() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 binary 与 tcp。
        MetConfiguration configuration = this.outgoing("binary", true, true);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=binary", "--framed=true", //
                "--multiplexed=true", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 通过公开客户端执行当前场景，并断言官方对端返回的结果。
            String value = "multiplexed 中文";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void officialCompactMultiplexedCallsMeteor() throws Exception {
        // Meteor 公开监听器承载本场景的 compact 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("compact", true, true);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=compact", "--framed=true", //
                    "--multiplexed=true", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=echo", "--value=official → Meteor 中文🙂")) {
                peer.requireResult("official → Meteor 中文🙂");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorCompactMultiplexedCallsOfficial() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 compact 与 tcp。
        MetConfiguration configuration = this.outgoing("compact", true, true);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=compact", "--framed=true", //
                "--multiplexed=true", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 通过公开客户端执行当前场景，并断言官方对端返回的结果。
            String value = "multiplexed 中文";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void officialJsonMultiplexedCallsMeteor() throws Exception {
        // Meteor 公开监听器承载本场景的 json 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("json", true, true);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=json", "--framed=true", //
                    "--multiplexed=true", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=echo", "--value=official → Meteor 中文🙂")) {
                peer.requireResult("official → Meteor 中文🙂");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorJsonMultiplexedCallsOfficial() throws Exception {
        // 官方服务端独立持有 Processor；Meteor 只配置出站 json 与 tcp。
        MetConfiguration configuration = this.outgoing("json", true, true);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                "--transport=tcp", "--protocol=json", "--framed=true", //
                "--multiplexed=true", "--service-name=ExtendedInteropService", //
                "--port=0", "--path=/thrift"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 订阅发生在容器启动后，客户端不存在可供本地直调的服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", port, unitName);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);

            // 通过公开客户端执行当前场景，并断言官方对端返回的结果。
            String value = "multiplexed 中文";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedUnknownMethodIsRejected() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, false);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=false", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=bad-method", "--value=missing")) {
                peer.requireResult("application:1");
            }
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryFramedUnknownMultiplexedServiceIsRejected() throws Exception {
        // Meteor 公开监听器承载本场景的 binary 编码与 tcp 传输。
        MetConfiguration configuration = this.incoming("binary", true, true);
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布普通业务实现，官方 Processor 不参与 Meteor 调用。
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 隔离进程中的官方 Client 使用实际监听端口，只执行当前场景。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                    "--transport=tcp", "--protocol=binary", "--framed=true", //
                    "--multiplexed=true", "--service-name=ExtendedInteropService", //
                    "--port=" + port, "--path=/thrift", "--scenario=bad-mux", "--value=missing")) {
                peer.requireResult("rejected:TApplicationException");
            }
        }
    }

    private MetConfiguration incoming(String encoding, boolean framed, boolean multiplexed) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.setQueueMinPoolSize(4);
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("thrift") //
                .option("encoding", encoding).option("framed", framed).option("multiplexed", multiplexed);
        return configuration;
    }

    private MetConfiguration outgoing(String encoding, boolean framed, boolean multiplexed) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.setQueueMinPoolSize(4).setDefaultTimeout(5000);
        configuration.connector("tcp").protocol("thrift") //
                .option("encoding", encoding).option("framed", framed).option("multiplexed", multiplexed);
        return configuration;
    }

    private MetConfiguration incomingHttp(String encoding, boolean multiplexed) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.setQueueMinPoolSize(4);
        configuration.connector("http").bind("127.0.0.1", 0).protocol("thrift-http").contextPath("/thrift") //
                .option("encoding", encoding).option("multiplexed", multiplexed);
        return configuration;
    }

    private MetConfiguration outgoingHttp(String encoding, boolean multiplexed) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.setQueueMinPoolSize(4).setDefaultTimeout(5000);
        configuration.connector("http").protocol("thrift-http").contextPath("/thrift") //
                .option("encoding", encoding).option("multiplexed", multiplexed);
        return configuration;
    }

    private Sample sample() {
        // 业务数据覆盖每类标量、容器和已设置的 optional 字段，不参与启动或调用。
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
