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

/** 消息大小限制同时约束压缩前与解压后数据。 */
@NxnRpc(name = "triple")
public class TripleLimitScenarioTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void oversizedCompressedRequestIsRejectedBeforeBusinessExecution() throws Exception {
        // 服务端对解压后的消息设置上限，客户端仍允许构造较大的合法请求。
        MetConfiguration incoming = this.configuration(true, 512);
        MetConfiguration outgoing = this.configuration(false, 32768);
        TripleDemoServiceImpl implementation = new TripleDemoServiceImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();

            // 重复内容压缩后很小，解压后的实际大小仍必须执行同一个消息上限。
            MetClient caller = client.getRsfClient(server.getListenAddress("triple"));
            Class<?>[] types = { TripleMessage.class };
            Object[] input = { TripleMessage.of("x".repeat(6000), 1) };
            MetFuture pending = caller.asyncInvoke(service, "echo", types, input);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(5, TimeUnit.SECONDS);
            });
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(8, status.getCode());
            assertEquals(0, implementation.getCalls());

            // 超限只终止当前调用；小消息仍通过同一客户端上下文正常完成。
            TripleDemoService remote = caller.getRemote(service);
            TripleMessage following = remote.echo(TripleMessage.of("small", 2));
            assertEquals("small", following.getText());
            assertEquals(3, following.getNumber());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void oversizedResponseFailsTheCurrentCall() throws Exception {
        // 服务端业务可返回大对象，但发送仍受显式消息上限约束。
        MetConfiguration incoming = this.configuration(true, 512);
        MetConfiguration outgoing = this.configuration(false, 32768);
        TripleDemoServiceImpl implementation = new TripleDemoServiceImpl() {
            @Override
            public TripleMessage echo(TripleMessage request) {
                return TripleMessage.of("x".repeat(6000), request.getNumber());
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();

            // 编码失败必须变为协议终态，调用方不能一直等待不存在的正文。
            MetClient caller = client.getRsfClient(server.getListenAddress("triple"));
            Class<?>[] types = { TripleMessage.class };
            Object[] input = { TripleMessage.of("small", 1) };
            MetFuture pending = caller.asyncInvoke(service, "echo", types, input);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(5, TimeUnit.SECONDS);
            });
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(8, status.getCode());
        }
    }

    private MetConfiguration configuration(boolean listener, int maximum) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (listener) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("triple").contextPath("/rpc").option("streamBody", true) //
                .option("maxMessageSize", maximum).option("compression", "gzip");
        return configuration;
    }
}
