/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix.types;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 一次网络场景只验证一种 Java 字段类型的一个边界。 */
@ParameterizedClass(autoCloseArguments = false, name = "java-basic/self/{0}/direct/type={1}/vector={2}", quoteTextArguments = false)
@MethodSource("configurations")
@NxnRpc(name = "rsf", version = "1", cases = "java-basic/self/rsf-json/*")
@NxnRpc(name = "rsf", version = "1", cases = "java-basic/self/rsf-java/*")
@NxnRpc(name = "dubbo", cases = "java-basic/self/dubbo-hessian2/*")
@NxnRpc(name = "hprose", cases = "java-basic/self/hprose/*")
@NxnRpc(name = "hessian", version = "2", cases = "java-basic/self/hessian-2/*")
@NxnRpc(name = "jsonrpc", version = "2.0", cases = "java-basic/self/jsonrpc-json/*")
@NxnRpc(name = "xmlrpc", cases = "java-basic/self/xmlrpc-struct/*")
@NxnRpc(name = "soap", version = "1.1", cases = "java-basic/self/soap-literal/*")
@NxnRpc(name = "grpc", cases = "java-basic/self/grpc-custom-json/*")
@NxnRpc(name = "grpc", cases = "java-basic/self/grpc-protobuf/*")
@NxnRpc(name = "grpc-web", cases = "java-basic/self/grpc-web-custom-json/*")
@NxnRpc(name = "grpc-web", cases = "java-basic/self/grpc-web-protobuf/*")
@NxnRpc(name = "connect", version = "1", cases = "java-basic/self/connect-json/*")
@NxnRpc(name = "connect", version = "1", cases = "java-basic/self/connect-protobuf/*")
@NxnRpc(name = "triple", cases = "java-basic/self/triple-java-hessian2/*")
@NxnRpc(name = "triple", cases = "java-basic/self/triple-protobuf/*")
@NxnRpc(name = "thrift", cases = "java-basic/self/thrift-binary-framed/*")
@NxnRpc(name = "oncrpc", version = "2", cases = "java-basic/self/oncrpc-xdr/*")
public class JavaBasicTypeMatrixTest {
    @TempDir
    public Path data;
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

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void typedFieldFollowsItsDeclaredProtocolMapping() throws Exception {
        // 两端从公开 bootstrap 构建，消费者不监听，也没有本地服务可绕过网络。
        String group = "java-basic-" + UUID.randomUUID();
        var incoming = this.profile.providerConfiguration().setDefaultGroup(group);
        incoming.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        var outgoing = this.profile.consumerConfiguration().setDefaultGroup(group);
        outgoing.setDataHome(Files.createDirectory(this.data.resolve("consumer")));
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
            assertEquals(request.getClass(), response.getClass(), this.vector);
            Object actual = JavaTypeContracts.value(response);
            Object expected = expectation.expectedValue(this.expected);
            if (expected instanceof byte[] value) {
                assertArrayEquals(value, (byte[]) actual, this.vector);
            } else if (expected instanceof int[] value) {
                assertArrayEquals(value, (int[]) actual, this.vector);
            } else if (expected instanceof String[] value) {
                assertArrayEquals(value, (String[]) actual, this.vector);
            } else if (expected instanceof Float value) {
                int expectedBits = Float.floatToRawIntBits(value);
                int actualBits = Float.floatToRawIntBits((Float) actual);
                assertEquals(expectedBits, actualBits, this.vector);
            } else if (expected instanceof Double value) {
                long expectedBits = Double.doubleToRawLongBits(value);
                long actualBits = Double.doubleToRawLongBits((Double) actual);
                assertEquals(expectedBits, actualBits, this.vector);
            } else if (expected instanceof JavaTypeContracts.Customer customer) {
                JavaTypeContracts.Customer actualCustomer = (JavaTypeContracts.Customer) actual;
                assertEquals(customer.getCustomerId(), actualCustomer.getCustomerId());
                assertEquals(customer.getName(), actualCustomer.getName());
            } else {
                assertEquals(expected, actual, this.vector);
            }
        }
    }
}
