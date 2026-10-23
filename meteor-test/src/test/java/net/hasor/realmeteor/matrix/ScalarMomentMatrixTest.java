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
import net.hasor.example.contract.scalar.ScalarMoment;
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

/** Date 始终按 UTC 绝对时刻断言，秒级 XML-RPC 只承担整秒向量。 */
@RunWith(Parameterized.class)
public class ScalarMomentMatrixTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();
    private final ScalarValueProfile profile;
    private final String vector;

    public ScalarMomentMatrixTest(ScalarValueProfile profile, String vector) {
        this.profile = profile;
        this.vector = vector;
    }

    @Parameterized.Parameters(name = "moment/self/{0}/direct/{1}")
    public static Collection<Object[]> configurations() throws IOException {
        // 同一毫秒值用于全部可表达该精度的编码，不为 XML-RPC 静默截断共享向量。
        List<Object[]> result = new ArrayList<>();
        List<String> names = ScalarValueVectors.names("moments");
        for (ScalarValueProfile profile : ScalarValueProfile.values()) {
            if (!ScalarValueMatrixTest.selected(profile)) {
                continue;
            }
            for (String name : names) {
                long millis = ScalarValueVectors.moment(name).getInstant().getTime();
                if (profile.isXmlRpc() && millis % 1000 != 0) {
                    continue;
                }
                result.add(new Object[] { profile, name });
            }
        }
        return result;
    }

    @Test(timeout = 20000)
    public void utcInstantRetainsItsDeclaredPrecision() throws Exception {
        // 仅准备配置与独立数据目录，日期不会在启动前被格式化为业务字符串。
        String scope = "moment-" + UUID.randomUUID();
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

            // 两端走真实监听地址，IDL profile 在协议边界使用明确的 epoch 毫秒映射。
            var caller = consumer.getRsfClient(provider.getListenAddress(this.profile.protocolName()));
            ScalarValueService remote = this.profile.remote(caller, subscription);
            ScalarMoment request = ScalarValueVectors.moment(this.vector);
            ScalarMoment actual = remote.echoMoment(request);

            // Date 的毫秒值与 UTC Instant 同时匹配，不受测试进程显示时区影响。
            assertEquals("accepted:instant", actual.getReceipt());
            assertEquals(request.getInstant().getTime(), actual.getInstant().getTime());
            assertEquals(request.getInstant().toInstant(), actual.getInstant().toInstant());
        }
    }
}
