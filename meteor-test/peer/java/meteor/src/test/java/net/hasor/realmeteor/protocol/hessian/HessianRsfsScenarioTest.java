/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.hessian.HessianDemoService;
import net.hasor.example.protocol.hessian.HessianDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Timeout;

/** Hessian 序列化器通过 RSF 二进制协议在 TLS 连接中完成调用。 */
@NxnRpc(name = "rsf", version = "1")
public class HessianRsfsScenarioTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void hessianWithServerAuthentication() throws Exception {
        // 配置仅校验服务端证书的 TLS 连接，客户端不提供证书。
        MetConfiguration incoming = server("NONE");
        MetConfiguration outgoing = client(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后，发布并订阅使用 Hessian 编码的普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .protocol("rsf").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .protocol("rsf").serialize("Hessian").register();

            // 指定 RSFS 监听地址，验证经过真实 TLS 网络链路的往返结果。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf", "rsfs"));
            assertEquals("rsfs", server.getListenAddress("rsf", "rsfs").getSchema());
            assertEquals("secure:Hessian", remote.getRemote(service).echo("secure:Hessian"));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void hessianWithMutualTls() throws Exception {
        // 配置双方都提交证书的 TLS 连接，服务端必须验证客户端证书。
        MetConfiguration incoming = server("REQUIRE");
        MetConfiguration outgoing = client(true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后，发布并订阅使用 Hessian 编码的普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .protocol("rsf").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .protocol("rsf").serialize("Hessian").register();

            // 指定 RSFS 监听地址，验证双向认证后的 Hessian 编码往返。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf", "rsfs"));
            assertEquals("rsfs", server.getListenAddress("rsf", "rsfs").getSchema());
            assertEquals("secure:Hessian", remote.getRemote(service).echo("secure:Hessian"));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void concurrentHessianCallsKeepTheirOwnResults() throws Exception {
        // 多个请求通过双向 TLS 连接，统一使用 Hessian 编码。
        MetConfiguration incoming = server("REQUIRE");
        MetConfiguration outgoing = client(true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布接口，并通过客户端入口创建远程调用对象。
            server.start();
            client.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .protocol("rsf").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> service = client.publisher().rsfService(HessianDemoService.class) //
                    .protocol("rsf").serialize("Hessian").register();
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf", "rsfs"));

            // 先提交一批请求，再逐一核对每个 Future 对应的业务结果。
            List<MetFuture> calls = new ArrayList<>();
            Class<?>[] parameters = { String.class };
            for (int index = 0; index < 32; index++) {
                Object[] arguments = { "request-" + index };
                calls.add(remote.asyncInvoke(service, "echo", parameters, arguments));
            }
            for (int index = 0; index < calls.size(); index++) {
                Object result = calls.get(index).get(5, TimeUnit.SECONDS).getData();
                assertEquals("request-" + index, result);
            }
        }
    }

    private static MetConfiguration server(String clientAuth) {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("tcp").bind("127.0.0.1", 0);
        connector.option("tlsEnabled", true);
        connector.option("tlsKeyStore", "classpath:/certs/acceptance/server.p12");
        connector.option("tlsKeyStorePassword", "meteor-test");
        connector.option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12");
        connector.option("tlsTrustStorePassword", "meteor-test");
        connector.option("tlsClientAuth", clientAuth);
        connector.protocol("rsf").addressScheme("rsfs");
        return configuration;
    }

    private static MetConfiguration client(boolean provideCertificate) {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("tcp");
        connector.option("tlsEnabled", true);
        connector.option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12");
        connector.option("tlsTrustStorePassword", "meteor-test");
        if (provideCertificate) {
            connector.option("tlsKeyStore", "classpath:/certs/acceptance/client.p12");
            connector.option("tlsKeyStorePassword", "meteor-test");
        }
        connector.protocol("rsf").addressScheme("rsfs");
        return configuration;
    }
}
