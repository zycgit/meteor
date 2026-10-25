/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.triple.TripleMixedShapeService;
import net.hasor.example.protocol.triple.TripleMixedShapeServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 方法名相同时，线协议参数类型同时决定方法和本次 RPC 的流生命周期。 */
public class TripleMixedShapeScenarioTest {
    @Test(timeout = 15000)
    public void unaryAndServerFlowOverloadsKeepTheirOwnLifecycles() throws Exception {
        // 同一接口同时含 unary 与 Flow 重载，但 wire 参数类型分别为 int 和 String。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再通过正式服务入口发布和订阅混合形状的业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleMixedShapeService.class, new TripleMixedShapeServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleMixedShapeService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleMixedShapeService> service = client.publisher() //
                    .rsfService(TripleMixedShapeService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleMixedShapeService") //
                    .serialize("Hessian2").register();
            TripleMixedShapeService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 先走 unary，再走同名响应流；普通值不能被误判成 Publisher。
            assertEquals("unary:7", remote.source(7));
            List<String> values = new ArrayList<>();
            MetCaller.consume(remote.source("stream"), values::add).get(5, TimeUnit.SECONDS);
            assertEquals(List.of("stream-0", "stream-1", "stream-2"), values);
        }
    }

    @Test(timeout = 15000)
    public void unaryAndClientFlowOverloadsKeepTheirOwnLifecycles() throws Exception {
        // 同一接口同时含 unary 与 Flow 重载，但 wire 参数类型分别为 int 和 String。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再通过正式服务入口发布和订阅混合形状的业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleMixedShapeService.class, new TripleMixedShapeServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleMixedShapeService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleMixedShapeService> service = client.publisher() //
                    .rsfService(TripleMixedShapeService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleMixedShapeService") //
                    .serialize("Hessian2").register();
            TripleMixedShapeService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 第一条 wrapper 到达后选择输入流分支，读到半关闭后交付最终汇总。
            assertEquals("unary:11", remote.sink(11));
            String result = remote.sink(MetCaller.fromIterable(List.of("first", "second")));
            assertEquals("first,second", result);
        }
    }

    @Test(timeout = 15000)
    public void emptyClientFlowStillSelectsTheStreamingOverload() throws Exception {
        // 同一接口同时含 unary 与 Flow 重载，但 wire 参数类型分别为 int 和 String。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端先启动，再通过正式服务入口发布和订阅混合形状的业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleMixedShapeService.class, new TripleMixedShapeServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleMixedShapeService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleMixedShapeService> service = client.publisher() //
                    .rsfService(TripleMixedShapeService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleMixedShapeService") //
                    .serialize("Hessian2").register();
            TripleMixedShapeService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            // 空流没有第一条 wrapper；EOF 仍应选择请求流重载并正常返回空汇总。
            String result = remote.sink(MetCaller.fromIterable(List.of()));
            assertEquals("", result);

            // 空流结束不能把协议会话留在输入流状态，随后 unary 正常执行。
            assertEquals("unary:13", remote.sink(13));
        }
    }

    private MetConfiguration configuration(boolean listener) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (listener) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("triple").contextPath("/rpc").option("streamBody", true);
        return configuration;
    }
}
