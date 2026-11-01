/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.example.protocol.thrift.generated.InteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 真实 HTTP 传输上的 Thrift 编码、版本与标准消息互通场景。 */
@NxnRpc(name = "thrift")
public class ThriftHttpScenarioTest {

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void compactProxyCallsOverHttp1() throws Exception {
        // 显式设置 compact 编码，客户端选择 HTTP/1。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(1, "compact");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再通过公开 API 发布和订阅 IDL 对应的业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 类型代理从实际监听地址发起调用，结果经过 Thrift HTTP 响应返回。
            ThriftClient caller = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift-http")));
            ThriftDemoService remote = caller.getRemote(service);
            assertEquals("HTTP/1 compact 中文", remote.echo("HTTP/1 compact 中文"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void jsonProxyCallsOverHttp1() throws Exception {
        // 显式设置 json 编码，客户端选择 HTTP/1。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(1, "json");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再通过公开 API 发布和订阅 IDL 对应的业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 类型代理从实际监听地址发起调用，结果经过 Thrift HTTP 响应返回。
            ThriftClient caller = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift-http")));
            ThriftDemoService remote = caller.getRemote(service);
            assertEquals("HTTP/1 json 中文", remote.echo("HTTP/1 json 中文"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void binaryProxyCallsOverHttp2() throws Exception {
        // 不设置 encoding，验证默认 binary 编码，客户端选择 HTTP/2。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(2, "binary");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再通过公开 API 发布和订阅 IDL 对应的业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 类型代理从实际监听地址发起调用，结果经过 Thrift HTTP 响应返回。
            ThriftClient caller = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift-http")));
            ThriftDemoService remote = caller.getRemote(service);
            assertEquals("HTTP/2 binary 中文", remote.echo("HTTP/2 binary 中文"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void compactProxyCallsOverHttp2() throws Exception {
        // 显式设置 compact 编码，客户端选择 HTTP/2。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(2, "compact");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再通过公开 API 发布和订阅 IDL 对应的业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 类型代理从实际监听地址发起调用，结果经过 Thrift HTTP 响应返回。
            ThriftClient caller = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift-http")));
            ThriftDemoService remote = caller.getRemote(service);
            assertEquals("HTTP/2 compact 中文", remote.echo("HTTP/2 compact 中文"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void jsonProxyCallsOverHttp2() throws Exception {
        // 显式设置 json 编码，客户端选择 HTTP/2。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(2, "json");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再通过公开 API 发布和订阅 IDL 对应的业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 类型代理从实际监听地址发起调用，结果经过 Thrift HTTP 响应返回。
            ThriftClient caller = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift-http")));
            ThriftDemoService remote = caller.getRemote(service);
            assertEquals("HTTP/2 json 中文", remote.echo("HTTP/2 json 中文"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void h2cUpgradePreservesAnOfficialBinaryThriftMessage() throws Exception {
        // JDK HTTP 客户端独立发起 h2c 升级，正文由 Apache Thrift 官方协议编码。
        MetConfiguration configuration = this.serverConfiguration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // HTTP 正文直接包含 Thrift 消息，不附加 framed transport 长度头。
            TMemoryBuffer output = new TMemoryBuffer(128);
            TProtocol writer = new TBinaryProtocol(output);
            writer.writeMessageBegin(new TMessage("echo", TMessageType.CALL, 37));
            new InteropService.echo_args("h2c 中文").write(writer);
            writer.writeMessageEnd();
            byte[] body = Arrays.copyOf(output.getArray(), output.length());
            int port = server.getListenAddress("thrift-http").getPort();
            URI address = URI.create("http://127.0.0.1:" + port + "/thrift");
            HttpRequest request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/x-thrift") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)) //
                    .version(HttpClient.Version.HTTP_2).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // HTTP/2 响应版本证明真实升级成功，官方解码器验证消息身份与业务值。
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            TProtocol reader = new TBinaryProtocol(new TMemoryInputTransport(response.body()));
            TMessage message = reader.readMessageBegin();
            assertEquals("echo", message.name);
            assertEquals(TMessageType.REPLY, message.type);
            assertEquals(37, message.seqid);
            InteropService.echo_result result = new InteropService.echo_result();
            result.read(reader);
            reader.readMessageEnd();
            assertEquals("h2c 中文", result.getSuccess());
        }
    }

    private MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("thrift-http").contextPath("/thrift");
        return configuration;
    }

    private MetConfiguration clientConfiguration(int version, String encoding) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        MetConfiguration.ProtocolSettings protocol = configuration.connector("http").option("httpVersion", version) //
                .protocol("thrift-http").contextPath("/thrift");
        if (!StringUtils.equals("binary", encoding)) {
            protocol.option("encoding", encoding);
        }
        return configuration;
    }
}
