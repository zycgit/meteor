/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.dubbo;
import java.util.concurrent.Flow;
import net.hasor.example.protocol.dubbo.DubboUnsupportedFlowService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 经典 Dubbo 不支持的业务声明只拒绝协议端点，本地服务保持可用。 */
public class DubboPublicationScenarioTest {
    @Test(timeout = 10000)
    public void nonHessianSerializationKeepsLocalServiceCallable() throws Exception {
        // Dubbo Hessian2 的 wire ID 固定，JSON 声明不向该端点发布。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo");
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("dubbo");
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 本地服务正常注册，当前协议只拒绝不兼容的端点发布。
            MetBindInfo<DubboInteropService> service = context.publisher() //
                    .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .serialize("Json").register();

            // 服务保留在公开查询中，本地优先调用仍执行实际业务。
            assertNotNull(context.getServiceInfo(service.getBindID()));
            DubboInteropService local = context.getRsfClient().getRemote(service);
            assertEquals("local-json", local.echo("local-json"));
        }
    }

    @Test(timeout = 10000)
    public void flowSignatureKeepsLocalServiceCallable() throws Exception {
        // 经典 Dubbo 帧不能承载 Flow 签名，端点监听仍可正常建立。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo");
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("dubbo");
        DubboUnsupportedFlowService implementation = input -> input;
        Flow.Publisher<String> values = subscriber -> {
        };
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 本地服务正常注册，当前协议只拒绝不兼容的端点发布。
            MetBindInfo<DubboUnsupportedFlowService> service = context.publisher() //
                    .rsfService(DubboUnsupportedFlowService.class, implementation).register();

            // 服务保留在公开查询中，本地优先调用仍执行实际业务。
            assertNotNull(context.getServiceInfo(service.getBindID()));
            DubboUnsupportedFlowService local = context.getRsfClient().getRemote(service);
            assertSame(values, local.values(values));
        }
    }
}
