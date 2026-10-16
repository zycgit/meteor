/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.bootstrap;
import java.net.URLClassLoader;
import net.hasor.example.protocol.hprose.Echo;
import net.hasor.example.protocol.hprose.EchoService;
import net.hasor.example.protocol.hprose.SerializerExamples.SpiCoder;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.hprose.support.BootstrapTestSupport;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextSpiScenarioTest extends BootstrapTestSupport {
    @Test
    public void spiCodersAreDiscoveredPerContextAndUsedForRemoteCalls() throws Exception {
        // 两端配置同一个 SPI 序列化器名称，并故意使用不同大小写。
        MetConfiguration serverSettings = new MetConfiguration();
        MetConfiguration clientSettings = new MetConfiguration();
        serverSettings.connector("tcp") //
                .bind("127.0.0.1", 0) //
                .protocol("rsf") //
                .option("serializeType", "spi");
        clientSettings.connector("tcp").protocol("rsf").option("serializeType", "sPi");

        // 为每端提供独立 SPI 加载器，模拟应用分别发现和创建扩展。
        try (URLClassLoader serverLoader = this.spiLoader(SpiCoder.class.getName()); //
             URLClassLoader clientLoader = this.spiLoader(SpiCoder.class.getName()); //
             MetContext server = serverSettings.buildContext(serverLoader); //
             MetContext client = clientSettings.buildContext(clientLoader)) {
            // 启动后检查实例隔离、名称匹配和每端只初始化一次。
            server.start();
            client.start();

            SpiCoder serverCoder = (SpiCoder) server.getSerializeCoder("SPI");
            SpiCoder clientCoder = (SpiCoder) client.getSerializeCoder("SPI");
            assertSame(serverCoder, server.getSerializeCoder("spi"));
            assertSame(clientCoder, client.getSerializeCoder("sPi"));
            assertNotSame(serverCoder, clientCoder);
            assertSame(serverLoader, serverCoder.getLoader());
            assertSame(clientLoader, clientCoder.getLoader());
            assertEquals(1, serverCoder.getInitializations());
            assertEquals(1, clientCoder.getInitializations());

            // 用真实网络调用确认客户端和服务端使用各自的 SPI 编解码器。
            server.publisher().rsfService(Echo.class, new EchoService()).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            assertNull(service.getSerializeType());

            // 服务未单独指定编码时，调用使用协议配置中的 SPI 默认编码。
            Echo remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);
            assertEquals("SPI 中文", remote.echo("SPI 中文"));
            assertEquals(5, remote.add(2, 3));
        }
    }
}
