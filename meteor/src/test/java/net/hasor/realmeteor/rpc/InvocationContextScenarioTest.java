/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.rpc;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.*;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.rpc.MetRequestLocal;
import net.hasor.meteor.rpc.MetResponseLocal;
import org.junit.Test;
import static org.junit.Assert.*;

/** 通过公开启动与调用入口验证当前 RPC 请求和响应的线程上下文。 */
public class InvocationContextScenarioTest {
    @Test
    public void localInvocationBindsAndClearsContext() throws Exception {
        // 本地服务直接运行在调用线程，业务方法读取框架绑定的请求和响应。
        MetConfiguration configuration = this.localConfiguration();
        AtomicReference<MetRequest> observedRequest = new AtomicReference<>();
        AtomicReference<MetResponse> observedResponse = new AtomicReference<>();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> {
                observedRequest.set(new RequestView().current());
                observedResponse.set(new ResponseView().current());
                return "local:" + value;
            }).register();
            context.start();

            // 业务执行期间可见当前调用，返回调用方后线程上下文已经释放。
            MetFuture invocation = this.invoke(context.getRsfClient(), service, "value");
            MetResponse response = invocation.get(2, TimeUnit.SECONDS);
            assertEquals("local:value", response.getData());
            assertSame(invocation.getRequest(), observedRequest.get());
            assertSame(response, observedResponse.get());
            assertContextCleared();
        }
    }

    @Test
    public void localFailureClearsContext() throws Exception {
        // 业务方法确认上下文已经建立，然后抛出一个可识别异常。
        MetConfiguration configuration = this.localConfiguration();
        IllegalStateException cause = new IllegalStateException("local failure");
        AtomicReference<MetRequest> observedRequest = new AtomicReference<>();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> {
                observedRequest.set(new RequestView().current());
                throw cause;
            }).register();
            context.start();

            // 失败原样交给调用方，异常退出同样清理当前线程的绑定。
            MetFuture invocation = this.invoke(context.getRsfClient(), service, "value");
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                invocation.get(2, TimeUnit.SECONDS);
            });
            assertSame(cause, failure.getCause());
            assertSame(invocation.getRequest(), observedRequest.get());
            assertContextCleared();
        }
    }

    @Test
    public void nestedLocalInvocationRestoresOuterContext() throws Exception {
        // 同一线程中的外层业务调用会再次发起一次本地调用。
        MetConfiguration configuration = this.localConfiguration();
        AtomicReference<MetClient> clientRef = new AtomicReference<>();
        AtomicReference<MetBindInfo<Echo>> serviceRef = new AtomicReference<>();
        AtomicLong outerBefore = new AtomicLong();
        AtomicLong outerAfter = new AtomicLong();
        AtomicLong innerRequest = new AtomicLong();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, value -> {
                MetRequest current = new RequestView().current();
                if (StringUtils.equals("inner", value)) {
                    innerRequest.set(current.getRequestID());
                    return "inner";
                }

                outerBefore.set(current.getRequestID());
                Object result = this.invoke(clientRef.get(), serviceRef.get(), "inner").getData(2, TimeUnit.SECONDS);
                outerAfter.set(new RequestView().current().getRequestID());
                return "outer:" + result;
            }).register();
            serviceRef.set(service);
            clientRef.set(context.getRsfClient());
            context.start();

            // 内层结束后恢复外层绑定，最外层结束后再清理线程状态。
            assertEquals("outer:inner", this.invoke(clientRef.get(), service, "outer").getData(2, TimeUnit.SECONDS));
            assertEquals(outerBefore.get(), outerAfter.get());
            assertNotEquals(outerBefore.get(), innerRequest.get());
            assertContextCleared();
        }
    }

    @Test
    public void remoteInvocationBindsContextOnTheServerWorker() throws Exception {
        // 服务端监听真实 TCP 端口，客户端只声明出站 RSF 协议。
        MetConfiguration serverConfiguration = new MetConfiguration();
        serverConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfiguration = this.localConfiguration();
        AtomicReference<MetRequest> observedRequest = new AtomicReference<>();
        AtomicReference<MetResponse> observedResponse = new AtomicReference<>();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            server.publisher().rsfService(Echo.class, value -> {
                observedRequest.set(new RequestView().current());
                observedResponse.set(new ResponseView().current());
                return "remote:" + value;
            }).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();

            // 入站请求切换到服务端业务线程后仍具有对应的请求和响应上下文。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            assertEquals("remote:value", this.invoke(remote, service, "value").getData(3, TimeUnit.SECONDS));
            assertNotNull(observedRequest.get());
            assertFalse(observedRequest.get().isLocal());
            assertEquals(observedRequest.get().getRequestID(), observedResponse.get().getRequestID());
        }
    }

    @Test
    public void outboundFiltersSeeTheClientInvocationContext() throws Exception {
        // 服务端提供远程服务，客户端过滤器观察准备发送的本地请求。
        MetConfiguration serverConfiguration = new MetConfiguration();
        serverConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfiguration = this.localConfiguration();
        AtomicReference<MetRequest> observedRequest = new AtomicReference<>();
        AtomicReference<MetResponse> observedResponse = new AtomicReference<>();
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            server.publisher().rsfService(Echo.class, value -> "remote:" + value).register();
            client.publisher().bindFilter("observe-context", (request, response, chain) -> {
                observedRequest.set(new RequestView().current());
                observedResponse.set(new ResponseView().current());
                chain.doFilter(request, response);
            });
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();

            // 出站过滤器读取到的对象就是本次公开客户端调用创建的请求与响应。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            MetFuture invocation = this.invoke(remote, service, "value");
            MetResponse response = invocation.get(3, TimeUnit.SECONDS);
            assertEquals("remote:value", response.getData());
            assertSame(invocation.getRequest(), observedRequest.get());
            assertEquals(response.getRequestID(), observedResponse.get().getRequestID());
            assertContextCleared();
        }
    }

    private MetConfiguration localConfiguration() {
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        return configuration;
    }

    private MetFuture invoke(MetClient client, MetBindInfo<Echo> service, String value) {
        Class<?>[] parameterTypes = { String.class };
        Object[] arguments = { value };
        return client.asyncInvoke(service, "echo", parameterTypes, arguments);
    }

    private static void assertContextCleared() {
        assertNull(new RequestView().current());
        assertNull(new ResponseView().current());
    }

    public interface Echo {
        String echo(String value) throws Exception;
    }

    private static class RequestView extends MetRequestLocal {
        private MetRequest current() {
            return this.getRsfRequest();
        }
    }

    private static class ResponseView extends MetResponseLocal {
        private MetResponse current() {
            return this.getRsfResponse();
        }
    }
}
