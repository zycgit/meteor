/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import net.hasor.example.contract.scalar.ScalarValue;
import net.hasor.example.contract.scalar.ScalarValueProfile;
import net.hasor.example.contract.scalar.ScalarValueService;
import net.hasor.example.contract.scalar.ScalarValueServiceImpl;
import net.hasor.example.contract.scalar.ScalarValueVectors;
import net.hasor.meteor.MetContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 将已有线编码的零规范化明确报告，不能把这些配置宣称为 signed-zero 保留。 */
@ParameterizedClass(autoCloseArguments = false, name = "zero-normalization/self/{0}/direct", quoteTextArguments = false)
@MethodSource("configurations")
@NxnRpc(name = "dubbo", cases = "zero-normalization/self/dubbo-hessian2/direct")
@NxnRpc(name = "hessian", version = "2", cases = "zero-normalization/self/hessian-2/direct")
@NxnRpc(name = "jsonrpc", version = "2.0", cases = "zero-normalization/self/jsonrpc-json/direct")
@NxnRpc(name = "triple", cases = "zero-normalization/self/triple-java-hessian2/direct")
public class ScalarZeroNormalizationTest {
    @TempDir
    public Path data;
    private final ScalarValueProfile profile;

    public ScalarZeroNormalizationTest(ScalarValueProfile profile) {
        this.profile = profile;
    }
    public static Collection<Object[]> configurations() {
        Collection<Object[]> result = new ArrayList<>();
        for (ScalarValueProfile profile : ScalarValueProfile.values()) {
            if (ScalarValueMatrixTest.selected(profile) && ScalarValueMatrixTest.normalizesNegativeZero(profile)) {
                result.add(new Object[] { profile });
            }
        }
        return result;
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void negativeFloatZeroUsesTheDeclaredPositiveZeroWireValue() throws Exception {
        // 独立容器与原始负零向量，先确认测试输入保留负号位。
        String scope = "float-zero-" + UUID.randomUUID();
        var incoming = this.profile.providerConfiguration().setDefaultGroup(scope);
        incoming.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        var outgoing = this.profile.consumerConfiguration().setDefaultGroup(scope);
        outgoing.setDataHome(Files.createDirectory(this.data.resolve("consumer")));
        ScalarValue request = ScalarValueVectors.value("float-negative-zero");
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(request.getSingle()));
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            var subscription = this.profile.subscribe(consumer);
            this.profile.publish(provider, new ScalarValueServiceImpl());

            // 真实往返使用编码已声明的规范零，不通过修改请求向量消除差异。
            var caller = consumer.getRsfClient(provider.getListenAddress(this.profile.protocolName()));
            ScalarValueService remote = this.profile.remote(caller, subscription);
            ScalarValue actual = remote.echo(request);
            assertEquals("accepted:WHOLESALE", actual.getReceipt());
            assertEquals(Float.floatToRawIntBits(0.0f), Float.floatToRawIntBits(actual.getSingle()));
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void negativeDoubleZeroUsesTheDeclaredPositiveZeroWireValue() throws Exception {
        // 独立容器与原始负零向量，不复用 float 规范化场景的连接或结果。
        String scope = "double-zero-" + UUID.randomUUID();
        var incoming = this.profile.providerConfiguration().setDefaultGroup(scope);
        incoming.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        var outgoing = this.profile.consumerConfiguration().setDefaultGroup(scope);
        outgoing.setDataHome(Files.createDirectory(this.data.resolve("consumer")));
        ScalarValue request = ScalarValueVectors.value("double-negative-zero");
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(request.getDecimal()));
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            var subscription = this.profile.subscribe(consumer);
            this.profile.publish(provider, new ScalarValueServiceImpl());

            // 按实际线上语义验证正零，并由服务端收据证明本次调用进入了业务实现。
            var caller = consumer.getRsfClient(provider.getListenAddress(this.profile.protocolName()));
            ScalarValueService remote = this.profile.remote(caller, subscription);
            ScalarValue actual = remote.echo(request);
            assertEquals("accepted:WHOLESALE", actual.getReceipt());
            assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(actual.getDecimal()));
        }
    }
}
