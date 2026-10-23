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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.hasor.example.contract.scalar.ScalarValue;
import net.hasor.example.contract.scalar.ScalarValueProfile;
import net.hasor.example.contract.scalar.ScalarValueService;
import net.hasor.example.contract.scalar.ScalarValueServiceImpl;
import net.hasor.example.contract.scalar.ScalarValueVectors;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 每份标量向量独占一个 JUnit 实例，报告明确指出协议与具体边界值。 */
@RunWith(Parameterized.class)
public class ScalarValueMatrixTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();
    private final ScalarValueProfile profile;
    private final String vector;

    public ScalarValueMatrixTest(ScalarValueProfile profile, String vector) {
        this.profile = profile;
        this.vector = vector;
    }

    @Parameterized.Parameters(name = "scalar/self/{0}/direct/{1}")
    public static Collection<Object[]> configurations() throws IOException {
        // XML-RPC 的整数线类型只有 int32，不将超范围 long 假装成字符串原生互通。
        List<Object[]> result = new ArrayList<>();
        List<String> names = ScalarValueVectors.names("values");
        for (ScalarValueProfile profile : ScalarValueProfile.values()) {
            if (!selected(profile)) {
                continue;
            }
            for (String name : names) {
                if (name.endsWith("negative-zero") && normalizesNegativeZero(profile)) {
                    continue;
                }
                if (profile.isXmlRpc()) {
                    long integer = ScalarValueVectors.value(name).getInteger();
                    if (integer < Integer.MIN_VALUE || integer > Integer.MAX_VALUE) {
                        continue;
                    }
                }
                result.add(new Object[] { profile, name });
            }
        }
        return result;
    }

    @Test(timeout = 20000)
    public void selectedScalarValueRetainsItsExactJavaValue() throws Exception {
        // 每个边界值使用独立缓存与服务分组，两端都从公开 bootstrap 构建。
        String scope = "scalar-" + UUID.randomUUID();
        var incoming = this.profile.providerConfiguration().setDefaultGroup(scope);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        var outgoing = this.profile.consumerConfiguration().setDefaultGroup(scope);
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<?> subscription = this.profile.subscribe(consumer);
            assertTrue(consumer.getUpdater().queryAvailableAddresses(subscription.getBindID()).isEmpty());
            this.profile.publish(provider, new ScalarValueServiceImpl());

            // 明确指定真实网络地址，服务端读取实际 Java 类型并创建带业务收据的新对象。
            var caller = consumer.getRsfClient(provider.getListenAddress(this.profile.protocolName()));
            ScalarValueService remote = this.profile.remote(caller, subscription);
            ScalarValue request = ScalarValueVectors.value(this.vector);
            ScalarValue actual = remote.echo(request);

            // 整数不经过 double 比较；浮点按位比较同时验证极小值和负零没有折损。
            assertEquals("accepted:" + request.getKind().name(), actual.getReceipt());
            assertEquals(request.isFlag(), actual.isFlag());
            assertEquals(request.getInteger(), actual.getInteger());
            int expectedSingle = Float.floatToRawIntBits(request.getSingle());
            int actualSingle = Float.floatToRawIntBits(actual.getSingle());
            assertEquals(expectedSingle, actualSingle);
            long expectedDouble = Double.doubleToRawLongBits(request.getDecimal());
            long actualDouble = Double.doubleToRawLongBits(actual.getDecimal());
            assertEquals(expectedDouble, actualDouble);
            assertEquals(request.getKind(), actual.getKind());
            assertEquals(request.getNote(), actual.getNote());
        }
    }

    static boolean selected(ScalarValueProfile profile) {
        List<String> protocols = selection("meteor.matrix.protocols");
        List<String> profiles = selection("meteor.matrix.scalarProfiles");
        boolean protocol = selected(profile.protocolName(), protocols) || selected(profile.toString(), protocols);
        return protocol && selected(profile.toString(), profiles);
    }

    static boolean normalizesNegativeZero(ScalarValueProfile profile) {
        // Hessian2 的紧凑零编码与 JSON-RPC 的 BigDecimal 节点不保留零的符号位。
        return profile == ScalarValueProfile.DUBBO_HESSIAN2 || profile == ScalarValueProfile.HESSIAN2 //
                || profile == ScalarValueProfile.TRIPLE_HESSIAN2 || profile == ScalarValueProfile.JSONRPC;
    }

    private static List<String> selection(String property) {
        String value = System.getProperty(property, "*").toLowerCase(Locale.ROOT);
        return Arrays.stream(value.split(",")).map(String::trim).toList();
    }

    private static boolean selected(String value, List<String> selection) {
        return selection.contains("*") || selection.contains(value);
    }
}
