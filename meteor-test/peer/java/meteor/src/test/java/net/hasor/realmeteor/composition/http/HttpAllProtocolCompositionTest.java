/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.composition.http;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.net.URL;

import net.hasor.example.composition.http.HttpCompositionConfiguration;
import net.hasor.example.composition.http.HttpCompositionService;
import net.hasor.example.composition.http.HttpCompositionServiceImpl;
import net.hasor.example.composition.http.grpc.GrpcFamilyMessage;
import net.hasor.example.composition.http.grpc.GrpcFamilyService;
import net.hasor.example.composition.http.grpc.GrpcFamilyServiceImpl;
import net.hasor.example.composition.thrift.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 全插件类路径上，不同协议在一个真实 HTTP 端点共存。 */
@NxnRpc(name = "hprose")
@NxnRpc(name = "soap", version = "1.1")
@NxnRpc(name = "jsonrpc", version = "2.0")
@NxnRpc(name = "xmlrpc")
@NxnRpc(name = "hessian", version = "2")
@NxnRpc(name = "grpc")
@NxnRpc(name = "grpc-web")
@NxnRpc(name = "connect", version = "1")
@NxnRpc(name = "triple")
@NxnRpc(name = "thrift")
public class HttpAllProtocolCompositionTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void tenProtocolsShareOneHttp2Listener() throws Exception {
        // 一个 HTTP 端点挂载十个真实协议，客户端使用 HTTP/2 明文先验连接。
        MetConfiguration incoming = HttpCompositionConfiguration.incoming();
        MetConfiguration outgoing = HttpCompositionConfiguration.outgoing(2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 五个普通 Java 接口发布独立服务，每个服务只向自己的协议公开。
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("hprose:")) //
                    .group("hprose").aliasName("hprose", "echo").protocol("hprose").register();
            MetBindInfo<HttpCompositionService> hprose = client.publisher() //
                    .rsfService(HttpCompositionService.class).group("hprose") //
                    .aliasName("hprose", "echo").protocol("hprose").register();
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("soap:")) //
                    .group("soap").aliasName("soap", "echo").protocol("soap").register();
            MetBindInfo<HttpCompositionService> soap = client.publisher() //
                    .rsfService(HttpCompositionService.class).group("soap") //
                    .aliasName("soap", "echo").protocol("soap").register();
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("jsonrpc:")) //
                    .group("jsonrpc").aliasName("jsonrpc", "echo").protocol("jsonrpc").register();
            MetBindInfo<HttpCompositionService> jsonrpc = client.publisher() //
                    .rsfService(HttpCompositionService.class).group("jsonrpc") //
                    .aliasName("jsonrpc", "echo").protocol("jsonrpc").register();
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("xmlrpc:")) //
                    .group("xmlrpc").aliasName("xmlrpc", "echo").protocol("xmlrpc").register();
            MetBindInfo<HttpCompositionService> xmlrpc = client.publisher() //
                    .rsfService(HttpCompositionService.class).group("xmlrpc") //
                    .aliasName("xmlrpc", "echo").protocol("xmlrpc").register();
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("hessian:")) //
                    .group("hessian").aliasName("hessian", "echo").protocol("hessian").register();
            MetBindInfo<HttpCompositionService> hessian = client.publisher() //
                    .rsfService(HttpCompositionService.class).group("hessian") //
                    .aliasName("hessian", "echo").protocol("hessian").register();

            // IDL 资源只绑定到 Thrift 服务，不影响普通 Java 服务的发布。
            ThriftServiceResource thriftResource = new ThriftServiceResource(EchoService.class);
            server.publisher().rsfService(EchoService.Iface.class, value -> "thrift:" + value) //
                    .protocol("thrift-http").bindResource("thrift-http", thriftResource).register();
            MetBindInfo<EchoService.Iface> thrift = client.publisher().rsfService(EchoService.Iface.class) //
                    .protocol("thrift-http").bindResource("thrift-http", thriftResource).register();

            // gRPC 家族的四个协议共用同一份 Protobuf 定义，各自保持服务身份和路径。
            URL proto = this.getClass().getResource("/protocol/proto/composition/family.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            server.publisher().rsfService(GrpcFamilyService.class, new GrpcFamilyServiceImpl()) //
                    .group("grpc").aliasName("grpc", "meteor.composition.FamilyService") //
                    .protocol("grpc").serialize("Protobuf").bindResource("grpc", resource).register();
            MetBindInfo<GrpcFamilyService> grpc = client.publisher().rsfService(GrpcFamilyService.class) //
                    .group("grpc").aliasName("grpc", "meteor.composition.FamilyService") //
                    .protocol("grpc").serialize("Protobuf").bindResource("grpc", resource).register();
            server.publisher().rsfService(GrpcFamilyService.class, new GrpcFamilyServiceImpl()) //
                    .group("grpc-web").aliasName("grpc-web", "meteor.composition.FamilyService") //
                    .protocol("grpc-web").serialize("Protobuf").bindResource("grpc-web", resource).register();
            MetBindInfo<GrpcFamilyService> grpc_web = client.publisher().rsfService(GrpcFamilyService.class) //
                    .group("grpc-web").aliasName("grpc-web", "meteor.composition.FamilyService") //
                    .protocol("grpc-web").serialize("Protobuf").bindResource("grpc-web", resource).register();
            server.publisher().rsfService(GrpcFamilyService.class, new GrpcFamilyServiceImpl()) //
                    .group("connect").aliasName("connect", "meteor.composition.FamilyService") //
                    .protocol("connect").serialize("Protobuf").bindResource("connect", resource).register();
            MetBindInfo<GrpcFamilyService> connect = client.publisher().rsfService(GrpcFamilyService.class) //
                    .group("connect").aliasName("connect", "meteor.composition.FamilyService") //
                    .protocol("connect").serialize("Protobuf").bindResource("connect", resource).register();
            server.publisher().rsfService(GrpcFamilyService.class, new GrpcFamilyServiceImpl()) //
                    .group("triple").aliasName("triple", "meteor.composition.FamilyService") //
                    .protocol("triple").serialize("Protobuf").bindResource("triple", resource).register();
            MetBindInfo<GrpcFamilyService> triple = client.publisher().rsfService(GrpcFamilyService.class) //
                    .group("triple").aliasName("triple", "meteor.composition.FamilyService") //
                    .protocol("triple").serialize("Protobuf").bindResource("triple", resource).register();

            // 所有协议地址指向同一个监听端口，调用仍交给各自挂载的编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            assertEquals(port, server.getListenAddress("hprose").getPort());
            assertEquals(port, server.getListenAddress("soap").getPort());
            assertEquals(port, server.getListenAddress("jsonrpc").getPort());
            assertEquals(port, server.getListenAddress("xmlrpc").getPort());
            assertEquals(port, server.getListenAddress("hessian").getPort());
            assertEquals(port, server.getListenAddress("thrift-http").getPort());
            assertEquals(port, server.getListenAddress("grpc").getPort());
            assertEquals(port, server.getListenAddress("grpc-web").getPort());
            assertEquals(port, server.getListenAddress("connect").getPort());
            assertEquals(port, server.getListenAddress("triple").getPort());
            HttpCompositionService hproseRemote = client.getRsfClient(server.getListenAddress("hprose")) //
                    .getRemote(hprose);
            assertEquals("hprose:shared", hproseRemote.echo("shared"));
            HttpCompositionService soapRemote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(soap);
            assertEquals("soap:shared", soapRemote.echo("shared"));
            HttpCompositionService jsonrpcRemote = client.getRsfClient(server.getListenAddress("jsonrpc")) //
                    .getRemote(jsonrpc);
            assertEquals("jsonrpc:shared", jsonrpcRemote.echo("shared"));
            HttpCompositionService xmlrpcRemote = client.getRsfClient(server.getListenAddress("xmlrpc")) //
                    .getRemote(xmlrpc);
            assertEquals("xmlrpc:shared", xmlrpcRemote.echo("shared"));
            HttpCompositionService hessianRemote = client.getRsfClient(server.getListenAddress("hessian")) //
                    .getRemote(hessian);
            assertEquals("hessian:shared", hessianRemote.echo("shared"));
            ThriftClient thriftClient = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift-http")));
            assertEquals("thrift:shared", thriftClient.getRemote(thrift).echo("shared"));
            GrpcFamilyService grpcRemote = client.getRsfClient(server.getListenAddress("grpc")) //
                    .getRemote(grpc);
            assertEquals("family:shared", grpcRemote.echo(GrpcFamilyMessage.of("shared", 7)).getText());
            GrpcFamilyService grpc_webRemote = client.getRsfClient(server.getListenAddress("grpc-web")) //
                    .getRemote(grpc_web);
            assertEquals("family:shared", grpc_webRemote.echo(GrpcFamilyMessage.of("shared", 7)).getText());
            GrpcFamilyService connectRemote = client.getRsfClient(server.getListenAddress("connect")) //
                    .getRemote(connect);
            assertEquals("family:shared", connectRemote.echo(GrpcFamilyMessage.of("shared", 7)).getText());
            GrpcFamilyService tripleRemote = client.getRsfClient(server.getListenAddress("triple")) //
                    .getRemote(triple);
            assertEquals("family:shared", tripleRemote.echo(GrpcFamilyMessage.of("shared", 7)).getText());
        }
    }

}
