/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.hessian;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;

/** 启动后发布与订阅 Hessian 服务的完整应用入口。 */
public final class HessianExample {
    private HessianExample() {
    }

    public static void main(String[] args) throws Exception {
        // 选择 HTTP 端点、Hessian 协议挂载和 Hessian 2 线格式。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("hessian");
        incoming.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/rpc").option("version", 2);
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("hessian");
        outgoing.connector("http").protocol("hessian").contextPath("/rpc").option("version", 2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器先启动，再发布接口与订阅远程服务。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian").register();

            // 调用方使用普通 Java 代理，参数和结果经 Hessian 转换。
            HessianDemoService remote = client.getRsfClient(server.getListenAddress("hessian")).getRemote(service);
            System.out.println(remote.echo("Hello Hessian"));
        }
    }
}
