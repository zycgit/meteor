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
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** java.time 与旧 Date 分开验证；只比较 Instant 不足以证明时区类型保真。 */
@RunWith(Parameterized.class)
public class JavaTimeTypeMatrixTest {
    @Rule
    public final  TemporaryFolder data = new TemporaryFolder();
    private final JavaTypeProfile profile;
    private final String          type;
    private final String          vector;
    private final Object          expected;

    public JavaTimeTypeMatrixTest(JavaTypeProfile profile, String type, String vector, Object expected) {
        this.profile = profile;
        this.type = type;
        this.vector = vector;
        this.expected = expected;
    }

    @Parameterized.Parameters(name = "java-time/self/{0}/direct/type={1}/vector={2}")
    public static Collection<Object[]> configurations() {
        // 每种时间类型独立建服务，避免某一类型的发布拒绝掩盖其它类型。
        List<Object[]> result = new ArrayList<>();
        for (JavaTypeProfile profile : JavaTypeProfile.selected()) {
            for (Object[] value : JavaTypeVectors.time()) {
                String type = (String) value[0];
                if (JavaTypeProfile.selectedType(type)) {
                    result.add(new Object[] { profile, type, value[1], value[2] });
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("The Java time type selection has no matching cases");
        }
        return result;
    }

    @Test(timeout = 20000)
    public void javaTimeFieldFollowsItsDeclaredProtocolMapping() throws Exception {
        // 本轮使用固定业务时刻，主机时钟与测试等待超时不参与序列化向量。
        String group = "java-time-" + UUID.randomUUID();
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

            // 请求和返回值都保留 java.time 真实声明，不转 Date、epoch 或文本替身。
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
            assertEquals(this.vector, request.getClass(), response.getClass());
            Object actual = JavaTypeContracts.value(response);

            // 整体 equals 保留所有分量，带 offset/region 类型再明确检查其时间语义。
            Object expected = expectation.expectedValue(this.expected);
            assertEquals(this.vector, expected, actual);
            if (expected instanceof ZonedDateTime expectedTime) {
                ZonedDateTime actualTime = (ZonedDateTime) actual;
                assertEquals(expectedTime.getZone(), actualTime.getZone());
                assertEquals(expectedTime.getOffset(), actualTime.getOffset());
                assertEquals(expectedTime.toLocalDateTime(), actualTime.toLocalDateTime());
                assertEquals(expectedTime.toInstant(), actualTime.toInstant());
            } else if (expected instanceof OffsetDateTime expectedTime) {
                OffsetDateTime actualTime = (OffsetDateTime) actual;
                assertEquals(expectedTime.getOffset(), actualTime.getOffset());
                assertEquals(expectedTime.toLocalDateTime(), actualTime.toLocalDateTime());
            } else if (expected instanceof OffsetTime expectedTime) {
                OffsetTime actualTime = (OffsetTime) actual;
                assertEquals(expectedTime.getOffset(), actualTime.getOffset());
                assertEquals(expectedTime.toLocalTime(), actualTime.toLocalTime());
            }
        }
    }
}
