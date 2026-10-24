/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix.types;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.hasor.example.contract.types.JavaTypeContracts;
import net.hasor.example.contract.types.JavaTypeProfile;
import net.hasor.example.contract.types.JavaTypeVectors;
import net.hasor.meteor.MetContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** 一次网络场景只验证一种 Java 字段类型的一个边界。 */
@RunWith(Parameterized.class)
public class JavaBasicTypeMatrixTest {
    @Rule
    public final  TemporaryFolder data = new TemporaryFolder();
    private final JavaTypeProfile profile;
    private final String          type;
    private final String          vector;
    private final Object          expected;

    public JavaBasicTypeMatrixTest(JavaTypeProfile profile, String type, String vector, Object expected) {
        this.profile = profile;
        this.type = type;
        this.vector = vector;
        this.expected = expected;
    }

    @Parameterized.Parameters(name = "java-basic/self/{0}/direct/type={1}/vector={2}")
    public static Collection<Object[]> configurations() {
        // 参数顺序与报告名称一致，不在运行后由成功结果推断类型覆盖。
        List<Object[]> result = new ArrayList<>();
        for (JavaTypeProfile profile : JavaTypeProfile.selected()) {
            for (Object[] value : JavaTypeVectors.basic()) {
                String type = (String) value[0];
                if (JavaTypeProfile.selectedType(type)) {
                    result.add(new Object[] { profile, type, value[1], value[2] });
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("The Java basic type selection has no matching cases");
        }
        return result;
    }

    @Test(timeout = 20000)
    public void typedFieldFollowsItsDeclaredProtocolMapping() throws Exception {
        // 两端从公开 bootstrap 构建，消费者不监听，也没有本地服务可绕过网络。
        String group = "java-basic-" + UUID.randomUUID();
        var incoming = this.profile.providerConfiguration().setDefaultGroup(group);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        var outgoing = this.profile.consumerConfiguration().setDefaultGroup(group);
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
        Class<?> contract = JavaTypeContracts.contract(this.type);
        var expectation = JavaTypeMatrixExpectations.forCase(this.profile, this.type, this.vector);
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            // 发布期不支持的类型只接受已审阅的精确映射拒绝，不能把任意启动错误算成功。
            Object implementation = JavaTypeContracts.provider(this.type);
            if (expectation.publicationRejected()) {
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                    this.profile.publish(provider, contract, implementation);
                });
                expectation.assertRejection(failure, contract, this.profile);
                return;
            }
            this.profile.publish(provider, contract, implementation);
            var subscription = this.profile.subscribe(consumer, contract);

            // 值只写入真实类型字段，强契约沿用公共注解生成，不经过字符串适配。
            Object request = JavaTypeContracts.request(this.type, this.expected);
            var caller = consumer.getRsfClient(provider.getListenAddress(this.profile.protocolName()));
            Object remote = caller.getRemote(subscription);
            Method echo = contract.getMethods()[0];
            // 出站编码或远端的明确限制有独立拒绝断言，真实基础值仍必须精确往返。
            if (expectation.invocationRejected()) {
                InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> {
                    echo.invoke(remote, request);
                });
                expectation.assertRejection(failure, contract, this.profile);
                return;
            }
            Object response = echo.invoke(remote, request);

            // 精确校验字段类型与值；数组内容和浮点位模式分别处理。
            assertEquals(this.vector, request.getClass(), response.getClass());
            Object actual = JavaTypeContracts.value(response);
            Object expected = expectation.expectedValue(this.expected);
            if (expected instanceof byte[] value) {
                assertArrayEquals(this.vector, value, (byte[]) actual);
            } else if (expected instanceof int[] value) {
                assertArrayEquals(this.vector, value, (int[]) actual);
            } else if (expected instanceof String[] value) {
                assertArrayEquals(this.vector, value, (String[]) actual);
            } else if (expected instanceof Float value) {
                int expectedBits = Float.floatToRawIntBits(value);
                int actualBits = Float.floatToRawIntBits((Float) actual);
                assertEquals(this.vector, expectedBits, actualBits);
            } else if (expected instanceof Double value) {
                long expectedBits = Double.doubleToRawLongBits(value);
                long actualBits = Double.doubleToRawLongBits((Double) actual);
                assertEquals(this.vector, expectedBits, actualBits);
            } else if (expected instanceof JavaTypeContracts.Customer customer) {
                JavaTypeContracts.Customer actualCustomer = (JavaTypeContracts.Customer) actual;
                assertEquals(customer.getCustomerId(), actualCustomer.getCustomerId());
                assertEquals(customer.getName(), actualCustomer.getName());
            } else {
                assertEquals(this.vector, expected, actual);
            }
        }
    }
}
