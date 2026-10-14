/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.bootstrap;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import net.hasor.meteor.scenario.bootstrap.example.SelectiveServiceExample.Parent;
import net.hasor.meteor.scenario.bootstrap.example.SelectiveServiceExample.Published;
import net.hasor.meteor.scenario.bootstrap.example.SelectiveServiceExample.Service;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetIgnoreMethodTest {
    @Test
    public void publishedMethodListRespectsOverloadsInheritanceAndMethodKinds() throws Exception {
        // 接口方法扫描保留普通重载及可见默认方法，排除注解方法和静态方法。
        List<Method> methods = Arrays.asList(MetRuntimeUtils.getServiceMethods(Published.class));
        assertEquals(3, methods.size());
        assertTrue(methods.contains(Published.class.getMethod("echo", String.class)));
        assertTrue(methods.contains(Published.class.getMethod("echoint")));
        assertTrue(methods.contains(Parent.class.getMethod("inheritedVisible")));
    }

    @Test
    public void classPublicationDoesNotExposeObjectMethods() {
        // 显式以类发布时，同样不暴露继承自 Object 的方法。
        for (Method method : MetRuntimeUtils.getServiceMethods(Service.class)) {
            assertNotEquals(Object.class, method.getDeclaringClass());
        }

        // 按名称查找 Object 方法也必须得到禁止状态。
        MetException failure = assertThrows(MetException.class, () -> {
            MetRuntimeUtils.getServiceMethod(Service.class, "getClass", new Class<?>[0]);
        });
        assertEquals(ProtocolStatus.Forbidden, failure.getStatus());
    }

    @Test
    public void ignoreAnnotationDoesNotChangeDirectJavaCalls() {
        // 注解只约束 RPC 发布，直接调用实现及继承默认方法仍执行原 Java 行为。
        Service target = new Service();
        assertEquals("hidden", target.hidden());
        assertEquals("2", target.echo(2));
        assertEquals("hidden default", target.inheritedHidden());
        assertEquals(2, target.hiddenCalls());
    }
}
