/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.dubbo;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 普通 request/response 选项映射为 Dubbo attachments，不占用业务参数。 */
@NxnRpc(name = "dubbo")
public class DubboMetadataScenarioTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void userOptionsTravelAsRequestAndResponseAttachments() throws Exception {
        // 配置双方 Dubbo TCP 能力，客户端不开放监听端口。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("dubbo");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("dubbo");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("dubbo");
        outgoing.connector("tcp").protocol("dubbo");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 服务端在公开过滤器中读取请求选项，并写入独立的响应选项。
            server.publisher().bindFilter("attachments", (request, response, chain) -> {
                assertEquals("trace 中文", request.getOption("x-trace"));
                response.addOption("x-reply", "reply 中文");
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()).register();
            MetBindInfo<DubboInteropService> service = client.publisher() //
                    .rsfService(DubboInteropService.class).register();

            // 用户仅使用 withOptions 配置调用，结果与响应附件可以分别获取。
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo")) //
                    .withOptions(Map.of("x-trace", "trace 中文"));
            MetResponse response = caller.asyncInvoke(service, "metadata", new Class<?>[] { String.class }, //
                    new Object[] { "business" }).get(5, TimeUnit.SECONDS);
            assertEquals("business", response.getData());
            assertEquals("reply 中文", response.getOption("x-reply"));
        }
    }
}
