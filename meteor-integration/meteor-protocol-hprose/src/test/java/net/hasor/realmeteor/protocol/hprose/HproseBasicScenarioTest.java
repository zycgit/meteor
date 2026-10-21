/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose;
import net.hasor.example.protocol.hprose.Echo;
import net.hasor.example.protocol.hprose.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 插件基础验证：通过公开入口完成一次自身协议调用。 */
public class HproseBasicScenarioTest {
    @Test(timeout = 20000)
    public void bootstrapPublishesAndConsumesHproseService() throws Exception {
        // 服务端监听和客户端通道只挂载本插件，不依赖外部语言或其他协议。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("hprose");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("hprose");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("hprose");
        outgoing.connector("http").protocol("hprose");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动上下文，再发布并订阅相同的业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(Echo.class, new EchoService()).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();

            // 调用通过真实 HTTP 传输返回原始业务参数。
            Echo remote = client.getRsfClient(server.getListenAddress("hprose")).getRemote(service);
            assertEquals("basic hprose", remote.echo("basic hprose"));
        }
    }
}