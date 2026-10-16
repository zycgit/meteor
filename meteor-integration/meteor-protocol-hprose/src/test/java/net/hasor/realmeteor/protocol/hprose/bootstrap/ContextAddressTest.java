/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.bootstrap;
import java.io.File;
import java.util.List;
import net.hasor.example.protocol.hprose.Echo;
import net.hasor.example.protocol.hprose.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.hprose.support.BootstrapTestSupport;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ContextAddressTest extends BootstrapTestSupport {

    @Test
    public void configuredAddressPoolRoutesWithoutExplicitTarget() throws Exception {
        // 只配置服务地址本，通过正常客户端入口完成远程调用。
        MetConfiguration serverConfiguration = this.configuration();
        MetConfiguration clientConfiguration = this.configuration();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            // 发布服务并启动监听，将真实地址填入消费方的地址本。
            server.publisher().rsfService(Echo.class).toInstance(new EchoService()).register();

            server.start();
            InterAddress target = server.bindAddress("rsf");
            MetBindInfo<Echo> service = client.publisher() //
                    .rsfService(Echo.class).bindAddress(target).register();
            client.start();

            // 调用时不指定目标地址，由服务地址本完成选址。
            Echo remote = client.getRsfClient().getRemote(service);
            assertEquals("routed", remote.echo("routed"));
        }
    }

    @Test
    public void liveRpcUsesTheConfiguredServiceRoute() throws Exception {
        // 两个服务端返回不同标记，用真实返回值识别路由选中的实例。
        MetConfiguration firstConfiguration = this.configuration();
        MetConfiguration secondConfiguration = this.configuration();
        try (MetContext first = firstConfiguration.buildContext(); //
             MetContext second = secondConfiguration.buildContext()) {
            // 分别发布带有实例标记的服务，再启动两个监听端口。
            EchoService firstService = new EchoService() {
                @Override
                public String echo(String value) {
                    return "first";
                }
            };
            first.publisher().rsfService(Echo.class).toInstance(firstService).register();
            EchoService secondService = new EchoService() {
                @Override
                public String echo(String value) {
                    return "second";
                }
            };
            second.publisher().rsfService(Echo.class).toInstance(secondService).register();

            first.start();
            second.start();

            // 消费方维护两个提供者地址，通过路由规则决定实际调用目标。
            MetConfiguration config = this.configuration();
            try (MetContext client = config.buildContext()) {
                MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
                String id = service.getBindID();
                List<InterAddress> providers = List.of(first.bindAddress("rsf"), second.bindAddress("rsf"));
                client.getUpdater().appendAddress(id, providers);
                int secondPort = second.bindAddress("rsf").getPort();

                // 服务路由只保留第二个实例的端口。
                String serviceRule = """
                        <route>
                            <filter name="selected" from="addresses" item="a" test="a.port == %d"/>
                            <return value="selected"/>
                        </route>
                        """.formatted(secondPort);
                assertTrue(client.getUpdater().updateServiceRoute(id, serviceRule));
                client.start();

                // 任意参数都应调用到服务路由选中的第二个实例。
                Echo remote = client.getRsfClient().getRemote(service);
                assertEquals("second", remote.echo("any"));
            }
        }
    }

    @Test
    public void liveRpcSelectsAddressUsingActualArguments() throws Exception {
        // 两个服务端返回不同标记，用真实返回值识别路由选中的实例。
        MetConfiguration firstConfiguration = this.configuration();
        MetConfiguration secondConfiguration = this.configuration();
        try (MetContext first = firstConfiguration.buildContext(); //
             MetContext second = secondConfiguration.buildContext()) {
            // 分别发布带有实例标记的服务，再启动两个监听端口。
            EchoService firstService = new EchoService() {
                @Override
                public String echo(String value) {
                    return "first";
                }
            };
            first.publisher().rsfService(Echo.class).toInstance(firstService).register();
            EchoService secondService = new EchoService() {
                @Override
                public String echo(String value) {
                    return "second";
                }
            };
            second.publisher().rsfService(Echo.class).toInstance(secondService).register();

            first.start();
            second.start();

            // 消费方维护两个提供者地址，通过路由规则决定实际调用目标。
            MetConfiguration config = this.configuration();
            try (MetContext client = config.buildContext()) {
                MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
                String id = service.getBindID();
                List<InterAddress> providers = List.of(first.bindAddress("rsf"), second.bindAddress("rsf"));
                client.getUpdater().appendAddress(id, providers);
                int secondPort = second.bindAddress("rsf").getPort();

                // 启动后设置参数路由，特殊参数选第一个实例，其他参数选第二个。
                client.start();
                Echo remote = client.getRsfClient().getRemote(service);
                int firstPort = first.bindAddress("rsf").getPort();
                String argsRule = """
                        <route>
                            <bind name="port" value="%d"/>
                            <if test="utils.equals(args[0], 'special')">
                                <bind name="port" value="%d"/>
                            </if>
                            <filter name="selected" from="addresses" item="a" test="a.port == port"/>
                            <return value="selected"/>
                        </route>
                        """.formatted(secondPort, firstPort);
                assertTrue(client.getUpdater().updateArgsRoute(id, argsRule));

                // 同一个路由规则根据本次调用的实际参数选择不同提供者。
                assertEquals("first", remote.echo("special"));
                assertEquals("second", remote.echo("other"));
            }
        }
    }

    @Test
    public void disabledDiskCacheDoesNotCreateSnapshots() throws Exception {
        // 禁用缓存的上下文启动和关闭后，目录中不应生成快照。
        File home = this.temporary.newFolder();
        MetConfiguration config = this.configuration();
        config.setDataHome(home.toPath());
        try (MetContext runtime = config.buildContext()) {
            MetBindInfo<Echo> service = runtime.publisher().rsfService(Echo.class).register();
            String serviceId = service.getBindID();

            // 完成一次完整生命周期，关闭后检查缓存没有生成地址快照。
            runtime.start();
            runtime.close();
            assertTrue(runtime.getUpdater().queryAllAddresses(serviceId).isEmpty());
            assertEquals(0, home.list().length);
        }
    }
}
