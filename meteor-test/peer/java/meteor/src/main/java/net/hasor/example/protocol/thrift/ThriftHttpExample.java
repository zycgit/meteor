/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;

/** 通过公开启动、发布与订阅 API 执行一个完整的 Thrift HTTP 调用。 */
public final class ThriftHttpExample {
    private ThriftHttpExample() {
    }

    public static void main(String[] args) throws Exception {
        // 两端选择相同编码与传输挂载；IDL 生成类仅提供本地方法和字段元数据。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("thrift-http");
        incoming.connector("http").bind("127.0.0.1", 0) //
                .protocol("thrift-http").contextPath("/thrift").option("encoding", "binary");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("thrift-http");
        outgoing.connector("http").protocol("thrift-http").contextPath("/thrift").option("encoding", "binary");
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 启动容器后发布普通业务实现，再订阅对应的生成服务契约。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", resource).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", resource).register();

            // Thrift 客户端适配器保留 IDL 声明的返回类型和异常类型。
            ThriftClient caller = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift-http")));
            ThriftDemoService remote = caller.getRemote(service);
            System.out.println(remote.echo("Hello Thrift"));
            System.out.println(remote.add(19, 23));
        }
    }
}
