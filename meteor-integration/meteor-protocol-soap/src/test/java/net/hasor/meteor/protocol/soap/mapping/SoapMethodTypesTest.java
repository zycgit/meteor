/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.mapping;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JavaType;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SoapMethodTypesTest {
    @Test
    public void methodTypeVariableShadowsTheServiceTypeVariable() throws Exception {
        // 接口的 T 已绑定 User，方法重新声明的 <T> 仍以 Object 为上界。
        Method method = ShadowBase.class.getMethod("echo", Object.class);
        SoapMethodTypes types = new SoapMethodTypes(SoapXmlMapping.newMapper(), ShadowService.class, method);

        // 参数和返回值都不能误绑定到接口的 User。
        assertEquals(Object.class, types.getParameterTypes()[0].getRawClass());
        assertEquals(Object.class, types.getReturnType().getRawClass());
    }

    @Test
    public void shadowingMethodVariableUsesItsOwnDeclaredBound() throws Exception {
        // 同名方法变量具有 Number 上界，嵌套在 List 中也不能误用接口的 User。
        Method method = BoundedBase.class.getMethod("echo", List.class);
        SoapMethodTypes types = new SoapMethodTypes(SoapXmlMapping.newMapper(), BoundedService.class, method);

        // 只按声明上界生成固定类型，不根据某次调用的数值子类推断。
        assertEquals(Number.class, types.getParameterTypes()[0].getContentType().getRawClass());
        assertEquals(Number.class, types.getReturnType().getContentType().getRawClass());
    }

    @Test
    public void methodBoundCanStillReferToTheServiceTypeVariable() throws Exception {
        // 方法的 <R extends T> 引用接口 T，没有同名遮蔽，仍应得到 User。
        Method method = OwnerBoundBase.class.getMethod("echo", Map.class);
        SoapMethodTypes types = new SoapMethodTypes(SoapXmlMapping.newMapper(), OwnerBoundService.class, method);

        // 保留 Map<String,List<R>> 的容器层次及最内层的声明绑定。
        JavaType parameterItem = types.getParameterTypes()[0].getContentType().getContentType();
        JavaType returnItem = types.getReturnType().getContentType().getContentType();
        assertEquals(User.class, parameterItem.getRawClass());
        assertEquals(User.class, returnItem.getRawClass());
    }

    private interface ShadowBase<T> {
        <T> T echo(T value);
    }

    private interface ShadowService extends ShadowBase<User> {
    }

    private interface BoundedBase<T> {
        <T extends Number> List<T> echo(List<T> value);
    }

    private interface BoundedService extends BoundedBase<User> {
    }

    private interface OwnerBoundBase<T> {
        <R extends T> Map<String, List<R>> echo(Map<String, List<R>> value);
    }

    private interface OwnerBoundService extends OwnerBoundBase<User> {
    }

    public static class User {
        public int id;
    }
}
