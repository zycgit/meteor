/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.triple.TripleDemoService;
import net.hasor.example.protocol.triple.TripleDemoServiceImpl;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 服务别名、分组和版本共同确定 Triple 的远端业务身份。 */
@NxnRpc(name = "triple")
public class TripleIdentityScenarioTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void groupHeaderSelectsTheCorrectProvider() throws Exception {
        // 相同协议别名下发布两个服务身份，只改变当前场景要验证的维度。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, this.implementation("first")) //
                    .group("blue").version("1.0") //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();
            server.publisher().rsfService(TripleDemoService.class, this.implementation("second")) //
                    .group("green").version("1.0") //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .group("green").version("1.0") //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();

            // Java 订阅身份映射为 Triple 的 group/version 头，选择目标提供者。
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage result = remote.echo(TripleMessage.of("request", 4));
            assertEquals("second", result.getText());
            assertEquals(4, result.getNumber());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void versionHeaderSelectsTheCorrectProvider() throws Exception {
        // 相同协议别名下发布两个服务身份，只改变当前场景要验证的维度。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, this.implementation("first")) //
                    .group("blue").version("1.0") //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();
            server.publisher().rsfService(TripleDemoService.class, this.implementation("second")) //
                    .group("blue").version("2.0") //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .group("blue").version("2.0") //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();

            // Java 订阅身份映射为 Triple 的 group/version 头，选择目标提供者。
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage result = remote.echo(TripleMessage.of("request", 4));
            assertEquals("second", result.getText());
            assertEquals(4, result.getNumber());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unknownGroupDoesNotFallBackToAnotherProvider() throws Exception {
        // 服务存在但 group 不同，不能因协议别名相同而选中错误提供者。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        TripleDemoServiceImpl implementation = new TripleDemoServiceImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, implementation) //
                    .group("blue").version("1.0") //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .group("missing").version("1.0") //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();

            // 错误在公开 Future 上可见，目标业务未执行。
            MetClient caller = client.getRsfClient(server.getListenAddress("triple"));
            Class<?>[] types = { TripleMessage.class };
            Object[] values = { TripleMessage.of("wrong target", 4) };
            MetFuture pending = caller.asyncInvoke(service, "echo", types, values);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(5, TimeUnit.SECONDS);
            });
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(12, status.getCode());
            assertEquals(0, implementation.getCalls());
        }
    }

    private TripleDemoServiceImpl implementation(String marker) {
        return new TripleDemoServiceImpl() {
            @Override
            public TripleMessage echo(TripleMessage request) {
                return TripleMessage.of(marker, request.getNumber());
            }
        };
    }

    private MetConfiguration configuration(boolean listener) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (listener) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("triple").contextPath("/rpc").option("streamBody", true).option("httpMode", "grpc");
        return configuration;
    }
}
