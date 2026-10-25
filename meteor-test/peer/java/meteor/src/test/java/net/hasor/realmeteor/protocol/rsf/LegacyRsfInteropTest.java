/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.rsf;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.hasor.example.protocol.rsf.RsfEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 当前 Meteor 与 Maven 正式版 Hasor RSF 4.2.5 通过 RSF/1 真实网络互通。 */
public class LegacyRsfInteropTest {
    @Test(timeout = 60000)
    public void legacyRsfSdkCallsMeteor() throws Exception {
        // 通过公开入口启动 Meteor，并显式使用旧 SDK 共同支持的 Java 序列化。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(6000);
        configuration.connector("tcp").bind(ContainerProcess.runnerHost(), 0).protocol("rsf");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(RsfEchoService.class, text -> text) //
                    .group("compat").name("RsfEcho").version("1.0.0") //
                    .protocol("rsf").serialize("Java").register();

            // 独立 SDK 消费者执行握手和调用，没有可供本地优先命中的服务实现。
            String text = "legacy-rsf-中文🙂";
            String encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
            String target = server.getListenAddress("rsf").toString();
            try (LegacyRsfPeerProcess peer = LegacyRsfPeerProcess.start("client", target, encoded)) {
                assertEquals(text, peer.awaitResult());
            }
        }
    }

    @Test(timeout = 60000)
    public void meteorCallsLegacyRsfSdk() throws Exception {
        // 旧版握手要求双方携带监听地址，Meteor 消费者也启动独立监听器。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(6000);
        configuration.connector("tcp").bind(ContainerProcess.runnerHost(), 0).protocol("rsf");
        try (LegacyRsfPeerProcess peer = LegacyRsfPeerProcess.start("server"); //
             MetContext client = configuration.buildContext()) {
            String target = peer.awaitAddress();
            client.start();
            MetBindInfo<RsfEchoService> service = client.publisher().rsfService(RsfEchoService.class) //
                    .group("compat").name("RsfEcho").version("1.0.0") //
                    .protocol("rsf").serialize("Java").register();

            // 对端是旧 SDK 的真实服务端，结果由普通接口代理返回。
            RsfEchoService remote = client.getRsfClient(new InterAddress(new URI(target))).getRemote(service);
            String text = "meteor-rsf-中文🙂";
            assertEquals(text, remote.echo(text));
        }
    }
}
