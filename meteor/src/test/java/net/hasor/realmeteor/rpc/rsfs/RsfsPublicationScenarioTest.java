/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.rpc.rsfs;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.rpc.rsfs.RsfsConfigurations;
import net.hasor.example.rpc.rsfs.RsfsEcho;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 服务使用 RSF 协议名发布，地址前缀区分明文和 TLS 端点。 */
public class RsfsPublicationScenarioTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void discoveredSecureAddressUsesTheRsfsConnector() throws Exception {
        // 只配置安全端点，默认协议为 RSF，实际发布地址为 rsfs。
        MetConfiguration incoming = RsfsConfigurations.server("server", "NONE");
        MetConfiguration outgoing = RsfsConfigurations.client(null, "trust");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> "discovered:" + value).protocol("rsf").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class).protocol("rsf").register();

            // 通过公开地址更新入口交付发现结果，让普通客户端自行选址。
            InterAddress address = server.getListenAddress("rsf", "rsfs");
            client.getUpdater().appendStaticAddress(service.getBindID(), List.of(address));
            RsfsEcho remote = client.getRsfClient().getRemote(service);
            assertEquals("rsf", server.getDefaultProtocol());
            assertEquals("rsfs", address.getSchema());
            assertEquals(List.of(address), client.getUpdater().queryAvailableAddresses(service.getBindID()));
            assertEquals("discovered:value", remote.echo("value"));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void publicationRejectsAddressSchemeAsProtocolName() throws Exception {
        // 同一 RSF 协议同时挂载明文端点和 rsfs 安全端点。
        MetConfiguration incoming = RsfsConfigurations.server("server", "NONE");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        try (MetContext server = incoming.buildContext()) {
            server.start();

            // 地址前缀不能用作发布协议名，配置阶段直接拒绝。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                server.publisher().rsfService(RsfsEcho.class, value -> value).protocol("rsfs").register();
            });
            assertTrue(failure.getMessage().contains("unknown protocol: rsfs"));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void namedRsfServiceIsExposedThroughEveryRsfMount() throws Exception {
        // 同一 RSF 协议挂载到明文和 TLS 监听器，服务按协议名选择全部挂载。
        MetConfiguration incoming = RsfsConfigurations.server("server", "NONE");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = RsfsConfigurations.client(null, "trust");
        outgoing.connector("tcp").protocol("rsf");
        AtomicInteger calls = new AtomicInteger();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> {
                calls.incrementAndGet();
                return value;
            }).protocol("rsf").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class).register();

            // 两个端点使用同一个 RSF 协议实现，调用地址决定具体端点。
            InterAddress plain = server.getListenAddress("rsf", "rsf");
            RsfsEcho allowed = client.getRsfClient(plain).getRemote(service);
            assertEquals("allowed", allowed.echo("allowed"));
            RsfsEcho secure = client.getRsfClient(server.getListenAddress("rsf", "rsfs")).getRemote(service);
            assertEquals("secure", secure.echo("secure"));
            assertEquals(2, calls.get());
            assertNotEquals(plain.getPort(), server.getListenAddress("rsf", "rsfs").getPort());
        }
    }

}
