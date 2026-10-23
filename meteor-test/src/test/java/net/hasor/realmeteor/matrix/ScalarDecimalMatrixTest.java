/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.hasor.example.contract.scalar.ScalarDecimal;
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

/** BigDecimal 使用精确声明类型；XML-RPC 没有对应任意精度线类型。 */
@RunWith(Parameterized.class)
public class ScalarDecimalMatrixTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();
    private final ScalarValueProfile profile;
    private final String vector;

    public ScalarDecimalMatrixTest(ScalarValueProfile profile, String vector) {
        this.profile = profile;
        this.vector = vector;
    }

    @Parameterized.Parameters(name = "decimal/self/{0}/direct/{1}")
    public static Collection<Object[]> configurations() throws IOException {
        // 未给 XML-RPC 添加字符串适配器，其原生不支持范围明确记录在类型清单中。
        List<Object[]> result = new ArrayList<>();
        List<String> names = ScalarValueVectors.names("decimals");
        for (ScalarValueProfile profile : ScalarValueProfile.values()) {
            if (profile.isXmlRpc() || !ScalarValueMatrixTest.selected(profile)) {
                continue;
            }
            for (String name : names) {
                result.add(new Object[] { profile, name });
            }
        }
        return result;
    }

    @Test(timeout = 20000)
    public void exactDecimalRetainsEveryDigitAndItsScale() throws Exception {
        // 每份精确小数向量都拥有独立业务实例与上下文，不通过浮点中转准备请求。
        String scope = "decimal-" + UUID.randomUUID();
        var incoming = this.profile.providerConfiguration().setDefaultGroup(scope);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        var outgoing = this.profile.consumerConfiguration().setDefaultGroup(scope);
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            var subscription = this.profile.subscribe(consumer);
            this.profile.publish(provider, new ScalarValueServiceImpl());

            // 原生编码保留 BigDecimal，IDL 的文本字段只映射小数自身，不封装整个对象。
            var caller = consumer.getRsfClient(provider.getListenAddress(this.profile.protocolName()));
            ScalarValueService remote = this.profile.remote(caller, subscription);
            ScalarDecimal request = ScalarValueVectors.decimal(this.vector);
            ScalarDecimal actual = remote.echoDecimal(request);

            // BigDecimal.equals 同时约束值和 scale，避免小数位丢失或被 double 近似。
            assertEquals("accepted:amount", actual.getReceipt());
            assertEquals(request.getAmount(), actual.getAmount());
            assertEquals(request.getAmount().scale(), actual.getAmount().scale());
        }
    }
}
