/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.util.List;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetUpdater;
import org.junit.Test;
import static org.junit.Assert.*;

public class XmlRoutingTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void realRpcRoutesByInternalEnvironmentMetadataAndEachInvocation() throws Exception {
        MetConfiguration eastConfiguration = new MetConfiguration();
        eastConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration westConfiguration = new MetConfiguration();
        westConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        try (MetContext east = eastConfiguration.buildContext(); //
             MetContext west = westConfiguration.buildContext()) {
            // 两个远端返回不同标记，客户端只声明服务，不提供本地实现。
            east.publisher().rsfService(Echo.class, value -> "east").register();
            west.publisher().rsfService(Echo.class, value -> "west").register();
            east.start();
            west.start();
            int eastPort = east.bindAddress("rsf").getPort();
            int westPort = west.bindAddress("rsf").getPort();
            MetConfiguration configuration = new MetConfiguration().setApplicationName("east");
            configuration.connector("tcp").protocol("rsf");
            try (MetContext client = configuration.buildContext()) {
                MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
                service.setMetaData("east", eastPort);
                MetUpdater updater = client.getUpdater();
                String id = service.getBindID();
                updater.appendAddress(id, List.of(east.bindAddress("rsf"), west.bindAddress("rsf")));
                // 环境由框架填入，规则根据应用名称查询默认端口，并限制为当前单元的地址。
                String xml = """
                        <route>
                            <bind name="port" value="service.meta[env.applicationName]"/>
                            <if test="service.meta['port'] != null">
                                <bind name="port" value="service.meta['port']"/>
                            </if>
                            <if test="utils.equals(method.name, 'echo') and utils.equals(args[0], 'default')">
                                <bind name="port" value="service.meta[env.applicationName]"/>
                            </if>
                            <filter name="selected" from="addresses" item="a"
                                    test="a.port == port and utils.equals(a.formUnit, env.unitName)"/>
                            <return value="selected"/>
                        </route>
                        """;
                assertTrue(updater.updateServiceRoute(id, xml));
                client.start();
                Echo remote = client.getRsfClient().getRemote(service);
                assertEquals("east", remote.echo("ordinary"));

                // 修改服务元信息后下一次调用直接生效，参数仍可选择当前应用的默认端口。
                service.setMetaData("port", westPort);
                assertEquals("west", remote.echo("ordinary"));
                assertEquals("east", remote.echo("default"));
                assertEquals("west", remote.echo("ordinary"));

                // 无效更新保留旧规则；有效但运行失败的表达式由调用方感知。
                assertFalse(updater.updateServiceRoute(id, "<route><invalid/></route>"));
                assertEquals("west", remote.echo("ordinary"));
                assertTrue(updater.updateServiceRoute(id, "<route><return value='args[9]'/></route>"));
                assertThrows(IllegalArgumentException.class, () -> remote.echo("ordinary"));
            }
        }
    }
}
