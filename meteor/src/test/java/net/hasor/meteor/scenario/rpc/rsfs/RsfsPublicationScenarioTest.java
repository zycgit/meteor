/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.rpc.rsfs;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.scenario.rpc.rsfs.support.RsfsConfigurations;
import net.hasor.meteor.scenario.rpc.rsfs.support.RsfsEcho;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import static org.junit.Assert.*;

/** 服务协议选择和地址发现保留 rsf 与 rsfs 的独立身份。 */
public class RsfsPublicationScenarioTest {
    @Test(timeout = 30000)
    public void discoveredSecureAddressUsesTheRsfsConnector() throws Exception {
        // 只配置安全协议，默认协议和实际发布地址都应为 rsfs。
        MetConfiguration incoming = RsfsConfigurations.server("server", "NONE");
        MetConfiguration outgoing = RsfsConfigurations.client(null, "trust");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> "discovered:" + value).protocol("rsfs").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class).protocol("rsfs").register();

            // 通过公开地址更新入口交付发现结果，让普通客户端自行选址。
            InterAddress address = server.bindAddress("rsfs");
            client.getUpdater().appendStaticAddress(service.getBindID(), List.of(address));
            RsfsEcho remote = client.getRsfClient().getRemote(service);
            assertEquals("rsf", server.getDefaultProtocol());
            assertEquals("rsfs", address.getSchema());
            assertEquals(List.of(address), client.getUpdater().queryAvailableAddresses(service.getBindID()));
            assertEquals("discovered:value", remote.echo("value"));
        }
    }

    @Test(timeout = 30000)
    public void secureOnlyServiceIsNotExposedThroughPlainRsf() throws Exception {
        // 同一应用使用两个独立监听器，服务只选择 rsfs。
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
            }).protocol("rsfs").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class).register();

            // 被选择的入口可调用；另一入口收到协议禁止状态且不得执行服务。
            RsfsEcho allowed = client.getRsfClient(server.bindAddress("rsfs")).getRemote(service);
            assertEquals("allowed", allowed.echo("allowed"));
            InterAddress plain = server.bindAddress("tcp-1", "rsf");
            MetFuture rejected = client.getRsfClient(plain) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "blocked" });
            ThrowingRunnable awaitRejected = () -> rejected.get(5, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class, awaitRejected);
            assertTrue(failure.getCause() instanceof MetException);
            assertEquals(ProtocolStatus.Forbidden, ((MetException) failure.getCause()).getStatus());
            assertEquals(1, calls.get());
            assertNotEquals(plain.getPort(), server.bindAddress("rsfs").getPort());
        }
    }

    @Test(timeout = 30000)
    public void logicalRsfServiceIsExposedThroughEveryRsfMount() throws Exception {
        // 同一 RSF 逻辑协议挂载到明文和 TLS 监听器，服务按逻辑名选择全部挂载。
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

            // 逻辑协议名优先于地址别名，两个端点都使用同一个 RSF 协议实现。
            InterAddress plain = server.bindAddress("tcp-1", "rsf");
            RsfsEcho allowed = client.getRsfClient(plain).getRemote(service);
            assertEquals("allowed", allowed.echo("allowed"));
            RsfsEcho secure = client.getRsfClient(server.bindAddress("rsfs")).getRemote(service);
            assertEquals("secure", secure.echo("secure"));
            assertEquals(2, calls.get());
            assertNotEquals(plain.getPort(), server.bindAddress("rsfs").getPort());
        }
    }

}
