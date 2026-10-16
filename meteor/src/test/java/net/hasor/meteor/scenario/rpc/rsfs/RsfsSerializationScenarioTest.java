/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.rpc.rsfs;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.scenario.rpc.rsfs.support.RsfsConfigurations;
import net.hasor.meteor.scenario.rpc.rsfs.support.RsfsEcho;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 通过公开发布和代理调用，逐一验证已注册编码在 TLS 上的往返。 */
public class RsfsSerializationScenarioTest {
    @Test(timeout = 30000)
    public void javaWithServerAuthentication() throws Exception {
        // 为 Java 调用配置仅验证服务端证书，两端使用独立上下文。
        MetConfiguration incoming = RsfsConfigurations.server("server", "NONE");
        MetConfiguration outgoing = RsfsConfigurations.client(null, "trust");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> "secure:" + value) //
                    .protocol("rsfs").serialize("Java").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class) //
                    .protocol("rsfs").serialize("Java").register();

            // 显式使用 rsfs 地址经过真实 TLS 连接，不能命中本地优先调用。
            RsfsEcho remote = client.getRsfClient(server.bindAddress("rsfs")).getRemote(service);
            assertEquals("rsfs", server.bindAddress("rsfs").getSchema());
            assertEquals("secure:Java", remote.echo("Java"));
        }
    }

    @Test(timeout = 30000)
    public void javaWithMutualTls() throws Exception {
        // 为 Java 调用配置双向认证，两端使用独立上下文。
        MetConfiguration incoming = RsfsConfigurations.server("server", "REQUIRE");
        MetConfiguration outgoing = RsfsConfigurations.client("client", "trust");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> "secure:" + value) //
                    .protocol("rsfs").serialize("Java").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class) //
                    .protocol("rsfs").serialize("Java").register();

            // 显式使用 rsfs 地址经过真实 TLS 连接，不能命中本地优先调用。
            RsfsEcho remote = client.getRsfClient(server.bindAddress("rsfs")).getRemote(service);
            assertEquals("rsfs", server.bindAddress("rsfs").getSchema());
            assertEquals("secure:Java", remote.echo("Java"));
        }
    }

    @Test(timeout = 30000)
    public void jsonWithServerAuthentication() throws Exception {
        // 为 Json 调用配置仅验证服务端证书，两端使用独立上下文。
        MetConfiguration incoming = RsfsConfigurations.server("server", "NONE");
        MetConfiguration outgoing = RsfsConfigurations.client(null, "trust");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> "secure:" + value) //
                    .protocol("rsfs").serialize("Json").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class) //
                    .protocol("rsfs").serialize("Json").register();

            // 显式使用 rsfs 地址经过真实 TLS 连接，不能命中本地优先调用。
            RsfsEcho remote = client.getRsfClient(server.bindAddress("rsfs")).getRemote(service);
            assertEquals("rsfs", server.bindAddress("rsfs").getSchema());
            assertEquals("secure:Json", remote.echo("Json"));
        }
    }

    @Test(timeout = 30000)
    public void jsonWithMutualTls() throws Exception {
        // 为 Json 调用配置双向认证，两端使用独立上下文。
        MetConfiguration incoming = RsfsConfigurations.server("server", "REQUIRE");
        MetConfiguration outgoing = RsfsConfigurations.client("client", "trust");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> "secure:" + value) //
                    .protocol("rsfs").serialize("Json").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class) //
                    .protocol("rsfs").serialize("Json").register();

            // 显式使用 rsfs 地址经过真实 TLS 连接，不能命中本地优先调用。
            RsfsEcho remote = client.getRsfClient(server.bindAddress("rsfs")).getRemote(service);
            assertEquals("rsfs", server.bindAddress("rsfs").getSchema());
            assertEquals("secure:Json", remote.echo("Json"));
        }
    }

    @Test(timeout = 30000)
    public void hessianWithServerAuthentication() throws Exception {
        // 为 Hessian 调用配置仅验证服务端证书，两端使用独立上下文。
        MetConfiguration incoming = RsfsConfigurations.server("server", "NONE");
        MetConfiguration outgoing = RsfsConfigurations.client(null, "trust");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> "secure:" + value) //
                    .protocol("rsfs").serialize("Hessian").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class) //
                    .protocol("rsfs").serialize("Hessian").register();

            // 显式使用 rsfs 地址经过真实 TLS 连接，不能命中本地优先调用。
            RsfsEcho remote = client.getRsfClient(server.bindAddress("rsfs")).getRemote(service);
            assertEquals("rsfs", server.bindAddress("rsfs").getSchema());
            assertEquals("secure:Hessian", remote.echo("Hessian"));
        }
    }

    @Test(timeout = 30000)
    public void hessianWithMutualTls() throws Exception {
        // 为 Hessian 调用配置双向认证，两端使用独立上下文。
        MetConfiguration incoming = RsfsConfigurations.server("server", "REQUIRE");
        MetConfiguration outgoing = RsfsConfigurations.client("client", "trust");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(RsfsEcho.class, value -> "secure:" + value) //
                    .protocol("rsfs").serialize("Hessian").register();
            MetBindInfo<RsfsEcho> service = client.publisher().rsfService(RsfsEcho.class) //
                    .protocol("rsfs").serialize("Hessian").register();

            // 显式使用 rsfs 地址经过真实 TLS 连接，不能命中本地优先调用。
            RsfsEcho remote = client.getRsfClient(server.bindAddress("rsfs")).getRemote(service);
            assertEquals("rsfs", server.bindAddress("rsfs").getSchema());
            assertEquals("secure:Hessian", remote.echo("Hessian"));
        }
    }
}
