/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.rpc.rsfs;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.scenario.rpc.rsfs.support.RsfsConfigurations;
import net.hasor.meteor.scenario.rpc.rsfs.support.RsfsEcho;
import org.junit.Test;
import static org.junit.Assert.*;

/** 身份校验失败必须终止连接，业务不得被明文或未认证请求执行。 */
public class RsfsAuthenticationScenarioTest {
    @Test(timeout = 30000)
    public void missingClientCertificateIsRejected() throws Exception {
        // 服务端要求客户端证书，而客户端只配置可信 CA。
        MetConfiguration incoming = RsfsConfigurations.server("server", "REQUIRE");
        MetConfiguration outgoing = RsfsConfigurations.client(null, "trust");
        AtomicInteger calls = new AtomicInteger();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> {
                calls.incrementAndGet();
                return value;
            }).protocol("rsfs").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class).protocol("rsfs").register();

            // 认证失败直接结束调用，不能等待业务超时，也不能执行服务。
            MetFuture call = client.getRsfClient(server.bindAddress("rsfs")) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, calls.get());
        }
    }

    @Test(timeout = 30000)
    public void untrustedClientCertificateIsRejected() throws Exception {
        // 客户端携带另一个 CA 签发的证书。
        MetConfiguration incoming = RsfsConfigurations.server("server", "REQUIRE");
        MetConfiguration outgoing = RsfsConfigurations.client("untrusted-client", "trust");
        AtomicInteger calls = new AtomicInteger();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> {
                calls.incrementAndGet();
                return value;
            }).protocol("rsfs").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class).protocol("rsfs").register();

            // 认证失败直接结束调用，不能等待业务超时，也不能执行服务。
            MetFuture call = client.getRsfClient(server.bindAddress("rsfs")) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, calls.get());
        }
    }

    @Test(timeout = 30000)
    public void untrustedServerCertificateIsRejected() throws Exception {
        // 客户端只信任另一个 CA，不能接受当前服务器。
        MetConfiguration incoming = RsfsConfigurations.server("server", "NONE");
        MetConfiguration outgoing = RsfsConfigurations.client(null, "untrusted-trust");
        AtomicInteger calls = new AtomicInteger();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> {
                calls.incrementAndGet();
                return value;
            }).protocol("rsfs").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class).protocol("rsfs").register();

            // 认证失败直接结束调用，不能等待业务超时，也不能执行服务。
            MetFuture call = client.getRsfClient(server.bindAddress("rsfs")) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, calls.get());
        }
    }

    @Test(timeout = 30000)
    public void serverHostnameMismatchIsRejected() throws Exception {
        // 服务器证书链可信，但 SAN 不包含实际连接目标。
        MetConfiguration incoming = RsfsConfigurations.server("wrong-host-server", "NONE");
        MetConfiguration outgoing = RsfsConfigurations.client(null, "trust");
        AtomicInteger calls = new AtomicInteger();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> {
                calls.incrementAndGet();
                return value;
            }).protocol("rsfs").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class).protocol("rsfs").register();

            // 认证失败直接结束调用，不能等待业务超时，也不能执行服务。
            MetFuture call = client.getRsfClient(server.bindAddress("rsfs")) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, calls.get());
        }
    }

    @Test(timeout = 30000)
    public void secureClientNeverFallsBackToAPlaintextServer() throws Exception {
        // 明文 RSF 与 TLS RSF 使用相同帧格式，但不能跨越传输安全边界。
        MetConfiguration incoming = new MetConfiguration();
        incoming.setDefaultTimeout(10000);
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = RsfsConfigurations.client(null, "trust");
        AtomicInteger calls = new AtomicInteger();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> {
                calls.incrementAndGet();
                return value;
            }).register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class).register();

            // 故意以不匹配的 scheme 访问对端端口，连接必须失败而不是降级。
            InterAddress bound = server.bindAddress("rsf");
            InterAddress target = new InterAddress("rsfs", bound.getHost(), bound.getPort(), bound.getFormUnit());
            MetFuture call = client.getRsfClient(target) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, calls.get());
        }
    }

    @Test(timeout = 30000)
    public void secureListenerRejectsAPlaintextRsfClient() throws Exception {
        // 明文 RSF 与 TLS RSF 使用相同帧格式，但不能跨越传输安全边界。
        MetConfiguration incoming = RsfsConfigurations.server("server", "NONE");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.setDefaultTimeout(10000);
        outgoing.connector("tcp").protocol("rsf");
        AtomicInteger calls = new AtomicInteger();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> {
                calls.incrementAndGet();
                return value;
            }).register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class).register();

            // 故意以不匹配的 scheme 访问对端端口，连接必须失败而不是降级。
            InterAddress bound = server.bindAddress("rsfs");
            InterAddress target = new InterAddress("rsf", bound.getHost(), bound.getPort(), bound.getFormUnit());
            MetFuture call = client.getRsfClient(target) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, calls.get());
        }
    }

}
