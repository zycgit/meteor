/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import net.hasor.example.contract.scalar.ScalarValue;
import net.hasor.example.contract.scalar.ScalarValueProfile;
import net.hasor.example.contract.scalar.ScalarValueService;
import net.hasor.example.contract.scalar.ScalarValueServiceImpl;
import net.hasor.example.contract.scalar.ScalarValueVectors;
import net.hasor.meteor.MetContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertEquals;

/** 将已有线编码的零规范化明确报告，不能把这些配置宣称为 signed-zero 保留。 */
@RunWith(Parameterized.class)
public class ScalarZeroNormalizationTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();
    private final ScalarValueProfile profile;

    public ScalarZeroNormalizationTest(ScalarValueProfile profile) {
        this.profile = profile;
    }

    @Parameterized.Parameters(name = "zero-normalization/self/{0}/direct")
    public static Collection<Object[]> configurations() {
        Collection<Object[]> result = new ArrayList<>();
        for (ScalarValueProfile profile : ScalarValueProfile.values()) {
            if (ScalarValueMatrixTest.selected(profile) && ScalarValueMatrixTest.normalizesNegativeZero(profile)) {
                result.add(new Object[] { profile });
            }
        }
        return result;
    }

    @Test(timeout = 20000)
    public void negativeFloatZeroUsesTheDeclaredPositiveZeroWireValue() throws Exception {
        // 独立容器与原始负零向量，先确认测试输入保留负号位。
        String scope = "float-zero-" + UUID.randomUUID();
        var incoming = this.profile.providerConfiguration().setDefaultGroup(scope);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        var outgoing = this.profile.consumerConfiguration().setDefaultGroup(scope);
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
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

    @Test(timeout = 20000)
    public void negativeDoubleZeroUsesTheDeclaredPositiveZeroWireValue() throws Exception {
        // 独立容器与原始负零向量，不复用 float 规范化场景的连接或结果。
        String scope = "double-zero-" + UUID.randomUUID();
        var incoming = this.profile.providerConfiguration().setDefaultGroup(scope);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        var outgoing = this.profile.consumerConfiguration().setDefaultGroup(scope);
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
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
