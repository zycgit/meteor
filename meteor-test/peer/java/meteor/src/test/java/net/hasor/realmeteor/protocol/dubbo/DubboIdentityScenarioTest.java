/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.dubbo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** Dubbo 接口别名、group 和 version 共同决定调用的目标服务。 */
@NxnRpc(name = "dubbo")
public class DubboIdentityScenarioTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void groupSelectsTheExactProvider() throws Exception {
        // 两个提供者使用相同协议接口名，只改变本场景要验证的服务身份。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, this.implementation("blue")) //
                    .group("blue").version("1.0").aliasName("dubbo", "example.Identity").register();
            server.publisher().rsfService(DubboInteropService.class, this.implementation("green")) //
                    .group("green").version("1.0").aliasName("dubbo", "example.Identity").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .group("green").version("1.0").aliasName("dubbo", "example.Identity").register();

            // wire group 选择 green，不能只因接口名相同就命中 blue。
            DubboInteropService remote = client.getRsfClient(server.getListenAddress("dubbo")).getRemote(service);
            assertEquals("green:request", remote.echo("request"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void versionSelectsTheExactProvider() throws Exception {
        // 两个提供者使用相同协议接口名，只改变本场景要验证的服务身份。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, this.implementation("old")) //
                    .group("example").version("1.0").aliasName("dubbo", "example.Identity").register();
            server.publisher().rsfService(DubboInteropService.class, this.implementation("new")) //
                    .group("example").version("2.0").aliasName("dubbo", "example.Identity").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .group("example").version("2.0").aliasName("dubbo", "example.Identity").register();

            // wire version 选择 2.0，不回退到另一个已存在版本。
            DubboInteropService remote = client.getRsfClient(server.getListenAddress("dubbo")).getRemote(service);
            assertEquals("new:request", remote.echo("request"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void missingGroupDoesNotFallBackToAnotherProvider() throws Exception {
        // 两个提供者使用相同协议接口名，只改变本场景要验证的服务身份。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, this.implementation("blue")) //
                    .group("blue").version("1.0").aliasName("dubbo", "example.Identity").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .group("missing").version("1.0").aliasName("dubbo", "example.Identity").register();

            // 身份不存在必须在调用方体现为错误，不能返回其它提供者的数据。
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            MetFuture pending = caller.asyncInvoke(service, "echo", new Class<?>[] { String.class }, //
                    new Object[] { "wrong target" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(5, TimeUnit.SECONDS);
            });
            assertNotNull(failure.getCause());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void differentServicesRemainIsolatedUnderConcurrency() throws Exception {
        // 两个提供者使用相同协议接口名，只改变本场景要验证的服务身份。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, this.implementation("first")) //
                    .name("first").aliasName("dubbo", "example.First").register();
            server.publisher().rsfService(DubboInteropService.class, this.implementation("second")) //
                    .name("second").aliasName("dubbo", "example.Second").register();
            MetBindInfo<DubboInteropService> first = client.publisher().rsfService(DubboInteropService.class) //
                    .name("first").aliasName("dubbo", "example.First").register();
            MetBindInfo<DubboInteropService> second = client.publisher().rsfService(DubboInteropService.class) //
                    .name("second").aliasName("dubbo", "example.Second").register();

            // 交错提交两个服务的请求，验证同一连接的路由与响应关联共同保持隔离。
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            List<MetFuture> calls = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                calls.add(caller.asyncInvoke(first, "echo", new Class<?>[] { String.class }, //
                        new Object[] { "request-" + index }));
                calls.add(caller.asyncInvoke(second, "echo", new Class<?>[] { String.class }, //
                        new Object[] { "request-" + index }));
            }
            for (int index = 0; index < 16; index++) {
                assertEquals("first:request-" + index, calls.get(index * 2).getData(5, TimeUnit.SECONDS));
                assertEquals("second:request-" + index, calls.get(index * 2 + 1).getData(5, TimeUnit.SECONDS));
            }
        }
    }

    private DubboInteropServiceImpl implementation(String marker) {
        return new DubboInteropServiceImpl() {
            @Override
            public String echo(String value) {
                return marker + ":" + value;
            }
        };
    }

    private MetConfiguration configuration(boolean listener) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo");
        configuration.setDefaultTimeout(5000);
        var tcp = configuration.connector("tcp");
        if (listener) {
            tcp.bind("127.0.0.1", 0);
        }
        tcp.protocol("dubbo");
        return configuration;
    }
}
