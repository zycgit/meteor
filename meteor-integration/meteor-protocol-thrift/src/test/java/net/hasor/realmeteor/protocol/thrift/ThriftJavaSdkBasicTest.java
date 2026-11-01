/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.THttpClient;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.layered.TFramedTransport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 本插件基础验证：同一 JUnit 进程内，Meteor 与 Apache SDK 经 TCP、HTTP 双向真实调用。 */
public class ThriftJavaSdkBasicTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialJavaClientCallsMeteorOverFramedTcp() throws Exception {
        // 通过正式 bootstrap 监听 binary/framed TCP，使用插件本地生成契约。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("thrift") //
                .option("encoding", "binary").option("framed", true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 官方生成客户端使用实际端口，没有 Meteor 客户端或本地调用捷径。
            int port = server.getListenAddress("thrift").getPort();
            try (TTransport transport = new TFramedTransport(new TSocket("127.0.0.1", port, 5000))) {
                transport.open();
                TBinaryProtocol protocol = new TBinaryProtocol(transport);
                ExtendedInteropService.Client remote = new ExtendedInteropService.Client(protocol);
                String expected = "Apache Thrift TCP → Meteor 中文🙂";
                assertEquals(expected, remote.echo(expected));
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void officialJavaClientCallsMeteorOverHttp() throws Exception {
        // HTTP 自身提供消息边界，不增加 TCP framed 长度前缀。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("thrift-http") //
                .contextPath("/thrift").option("encoding", "binary");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 官方 THttpClient 负责真实 HTTP，请求结果直接在当前测试断言。
            int port = server.getListenAddress("thrift-http").getPort();
            try (THttpClient transport = new THttpClient("http://127.0.0.1:" + port + "/thrift")) {
                transport.setConnectTimeout(5000);
                transport.setReadTimeout(5000);
                transport.open();
                TBinaryProtocol protocol = new TBinaryProtocol(transport);
                ExtendedInteropService.Client remote = new ExtendedInteropService.Client(protocol);
                String expected = "Apache Thrift HTTP → Meteor 中文🙂";
                assertEquals(expected, remote.echo(expected));
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorClientCallsOfficialJavaServerOverFramedTcp() throws Exception {
        // 同进程启动 Apache SDK 监听器，Meteor 只订阅接口，不发布本地实现。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.setDefaultTimeout(5000);
        configuration.connector("tcp").protocol("thrift") //
                .option("encoding", "binary").option("framed", true);
        try (ThriftSdkServer server = ThriftSdkServer.tcp(); //
             MetContext client = configuration.buildContext()) {
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // SDK Processor 中的业务前缀证明调用确实经过真实网络。
            String unit = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift", "127.0.0.1", server.getPort(), unit);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);
            assertEquals("thrift-sdk:Meteor TCP 中文🙂", remote.echo("Meteor TCP 中文🙂"));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorClientCallsOfficialJavaServerOverHttp() throws Exception {
        // JDK HTTP 仅承载官方 Processor，Meteor 不持有本地服务实现。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.setDefaultTimeout(5000);
        configuration.connector("http").protocol("thrift-http").contextPath("/thrift").option("encoding", "binary");
        try (ThriftSdkServer server = ThriftSdkServer.http(); //
             MetContext client = configuration.buildContext()) {
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 正式消费者代理访问 SDK 实际端口，不手写 Thrift 报文。
            String unit = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("thrift-http", "127.0.0.1", server.getPort(), unit);
            ThriftClient caller = new ThriftClient(client.getRsfClient(address));
            ThriftDemoService remote = caller.getRemote(service);
            assertEquals("thrift-sdk:Meteor HTTP 中文🙂", remote.echo("Meteor HTTP 中文🙂"));
        }
    }
}
