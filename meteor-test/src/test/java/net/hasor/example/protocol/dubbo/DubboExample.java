/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.dubbo;
import java.util.List;
import net.hasor.example.protocol.dubbo.interop.DubboInteropPerson;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;

/** 普通 Java 接口通过 Dubbo 二进制协议调用，默认使用 Hessian2，不需要 Dubbo SDK。 */
public final class DubboExample {
    private DubboExample() {
    }

    public static void main(String[] arguments) throws Exception {
        // 服务器显式开放监听，客户端只配置向外发起调用所需的 TCP 与 Dubbo 能力。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("dubbo");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("dubbo");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("dubbo");
        outgoing.connector("tcp").protocol("dubbo");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器均启动后再发布与订阅；协议别名对应原生 Dubbo 的服务接口名。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .group("example").version("1.0") //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .group("example").version("1.0") //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();

            // 用户经公开客户端调用普通、多参数、重载和泛型 DTO 方法。
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);
            System.out.println("echo: " + remote.echo("Hello Dubbo"));
            System.out.println("multiple arguments: " + remote.combine("hello", 2));
            System.out.println("overload: " + remote.overloaded(42));
            List<DubboInteropPerson> people = List.of(new DubboInteropPerson("Meteor", 18));
            System.out.println("DTO: " + remote.people(people).get(0).getAge());
        }
    }
}
