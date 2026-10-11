/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetIgnore;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import static org.junit.Assert.*;

public class MetIgnoreTest {
    public interface Parent {
        @MetIgnore
        default String inheritedHidden() {
            return "hidden default";
        }

        default String inheritedVisible() {
            return "visible default";
        }
    }

    public interface Published extends Parent {
        String echo(String value);

        @MetIgnore
        String echo(int value);

        @MetIgnore
        String hidden();

        default String echoint() {
            return "visible overload name";
        }

        static String helper() {
            return "static helper";
        }
    }

    /** 独立客户端契约故意不带注解，验证服务端不能只依赖客户端过滤。 */
    public interface RemoteView {
        String echo(String value);

        String echo(int value);

        String hidden();

        String inheritedHidden();

        String inheritedVisible();

        String echoint();

        String helper();

        String implementationOnly();
    }

    public static class Service implements Published {
        private final AtomicInteger hiddenCalls = new AtomicInteger();

        @Override
        public String echo(String value) {
            return value;
        }

        @Override
        public String echo(int value) {
            this.hiddenCalls.incrementAndGet();
            return Integer.toString(value);
        }

        @Override
        public String hidden() {
            this.hiddenCalls.incrementAndGet();
            return "hidden";
        }

        public String implementationOnly() {
            this.hiddenCalls.incrementAndGet();
            return "implementation only";
        }
    }

    @Test
    public void publishedMethodListRespectsOverloadsInheritanceAndMethodKinds() throws Exception {
        List<Method> methods = Arrays.asList(MetRuntimeUtils.getServiceMethods(Published.class));
        assertEquals(3, methods.size());
        assertTrue(methods.contains(Published.class.getMethod("echo", String.class)));
        assertTrue(methods.contains(Published.class.getMethod("echoint")));
        assertTrue(methods.contains(Parent.class.getMethod("inheritedVisible")));

        // 显式以类发布时，同样不暴露继承自 Object 的方法。
        for (Method method : MetRuntimeUtils.getServiceMethods(Service.class)) {
            assertNotEquals(Object.class, method.getDeclaringClass());
        }
        assertForbidden(() -> MetRuntimeUtils.getServiceMethod(Service.class, "getClass", new Class<?>[0]));
    }

    @Test
    public void localProxyRejectsIgnoredMethodsWithoutChangingDirectJavaCalls() throws Exception {
        Service target = new Service();
        try (MetContext context = new MetConfiguration().buildContext()) {
            MetBindInfo<Published> service = context.publisher().rsfService(Published.class, target).register();
            context.start();
            Published proxy = context.getRsfClient().getRemote(service);
            assertEquals("local", proxy.echo("local"));
            assertEquals("visible default", proxy.inheritedVisible());
            assertEquals("visible overload name", proxy.echoint());
            assertForbidden(() -> proxy.echo(1));
            assertForbidden(proxy::hidden);
            assertForbidden(proxy::inheritedHidden);
            assertEquals(0, target.hiddenCalls.get());

            // 注解只约束 RPC 暴露，不改变直接 Java 调用。
            assertEquals("hidden", target.hidden());
            assertEquals("2", target.echo(2));
            assertEquals("hidden default", target.inheritedHidden());
            assertEquals(2, target.hiddenCalls.get());
        }
    }

    @Test
    public void serverRejectsIgnoredAndUndeclaredMethodsFromAnUnrestrictedClient() throws Exception {
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("tcp").protocol("rsf");
        Service target = new Service();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Published.class, target).name("SelectiveService").register();
            var serviceBuilder = client.publisher().rsfService(RemoteView.class);
            serviceBuilder.name("SelectiveService");
            MetBindInfo<RemoteView> service = serviceBuilder.register();
            server.start();
            client.start();
            RemoteView remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);

            assertEquals("remote", remote.echo("remote"));
            assertEquals("visible default", remote.inheritedVisible());
            assertEquals("visible overload name", remote.echoint());
            assertForbidden(() -> remote.echo(1));
            assertForbidden(remote::hidden);
            assertForbidden(remote::inheritedHidden);
            assertForbidden(remote::helper);
            assertForbidden(remote::implementationOnly);
            assertEquals(0, target.hiddenCalls.get());
            assertEquals("still available", remote.echo("still available"));
        }
    }

    private static void assertForbidden(ThrowingRunnable action) {
        Throwable failure = assertThrows(Throwable.class, action);
        while (!(failure instanceof MetException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        assertTrue(failure.toString(), failure instanceof MetException);
        assertEquals(ProtocolStatus.Forbidden, ((MetException) failure).getStatus());
    }
}
