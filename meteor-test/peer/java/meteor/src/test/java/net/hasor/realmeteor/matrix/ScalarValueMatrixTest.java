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
import java.io.IOException;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.example.contract.scalar.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 每份标量向量独占一个 JUnit 实例，报告明确指出协议与具体边界值。 */
@ParameterizedClass(autoCloseArguments = false, name = "scalar/self/{0}/direct/{1}", quoteTextArguments = false)
@MethodSource("configurations")
@NxnRpc(name = "rsf", version = "1", cases = "scalar/self/rsf-json/*")
@NxnRpc(name = "dubbo", cases = "scalar/self/dubbo-hessian2/*")
@NxnRpc(name = "hprose", cases = "scalar/self/hprose/*")
@NxnRpc(name = "hessian", version = "2", cases = "scalar/self/hessian-2/*")
@NxnRpc(name = "jsonrpc", version = "2.0", cases = "scalar/self/jsonrpc-json/*")
@NxnRpc(name = "xmlrpc", cases = "scalar/self/xmlrpc-struct/*")
@NxnRpc(name = "soap", version = "1.1", cases = "scalar/self/soap-literal/*")
@NxnRpc(name = "grpc", cases = "scalar/self/grpc-custom-json/*")
@NxnRpc(name = "grpc", cases = "scalar/self/grpc-protobuf/*")
@NxnRpc(name = "grpc-web", cases = "scalar/self/grpc-web-custom-json/*")
@NxnRpc(name = "grpc-web", cases = "scalar/self/grpc-web-protobuf/*")
@NxnRpc(name = "connect", version = "1", cases = "scalar/self/connect-json/*")
@NxnRpc(name = "connect", version = "1", cases = "scalar/self/connect-protobuf/*")
@NxnRpc(name = "triple", cases = "scalar/self/triple-java-hessian2/*")
@NxnRpc(name = "triple", cases = "scalar/self/triple-protobuf/*")
@NxnRpc(name = "thrift", cases = "scalar/self/thrift-binary-framed/*")
@NxnRpc(name = "oncrpc", version = "2", cases = "scalar/self/oncrpc-xdr/*")
public class ScalarValueMatrixTest {
    @TempDir
    public Path data;
    private final ScalarValueProfile profile;
    private final String             vector;

    public ScalarValueMatrixTest(ScalarValueProfile profile, String vector) {
        this.profile = profile;
        this.vector = vector;
    }
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
        if (result.isEmpty()) {
            throw new IllegalArgumentException("The requested scalar protocol/profile matrix has no matching cases");
        }
        return result;
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void selectedScalarValueRetainsItsExactJavaValue() throws Exception {
        // 每个边界值使用独立缓存与服务分组，两端都从公开 bootstrap 构建。
        String scope = "scalar-" + UUID.randomUUID();
        var incoming = this.profile.providerConfiguration().setDefaultGroup(scope);
        incoming.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        var outgoing = this.profile.consumerConfiguration().setDefaultGroup(scope);
        outgoing.setDataHome(Files.createDirectory(this.data.resolve("consumer")));
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
        List<String> availableProtocols = new ArrayList<>();
        List<String> availableProfiles = new ArrayList<>();
        for (ScalarValueProfile available : ScalarValueProfile.values()) {
            availableProtocols.add(available.protocolName());
            availableProtocols.add(available.toString());
            availableProfiles.add(available.toString());
        }
        List<String> protocols = selection("meteor.matrix.protocols", availableProtocols);
        List<String> profiles = selection("meteor.matrix.scalarProfiles", availableProfiles);
        boolean protocol = selected(profile.protocolName(), protocols) || selected(profile.toString(), protocols);
        return protocol && selected(profile.toString(), profiles);
    }

    static boolean normalizesNegativeZero(ScalarValueProfile profile) {
        // Hessian2 的紧凑零编码与 JSON-RPC 的 BigDecimal 节点不保留零的符号位。
        return profile == ScalarValueProfile.DUBBO_HESSIAN2 || profile == ScalarValueProfile.HESSIAN2 //
                || profile == ScalarValueProfile.TRIPLE_HESSIAN2 || profile == ScalarValueProfile.JSONRPC;
    }

    private static List<String> selection(String property, List<String> available) {
        String value = System.getProperty(property, "*").toLowerCase(Locale.ROOT);
        List<String> selected = new ArrayList<>();
        for (String token : value.split(",", -1)) {
            String name = token.trim();
            boolean wildcard = StringUtils.equals(name, "*") || StringUtils.equals(name, "all");
            if (!wildcard && !available.contains(name)) {
                throw new IllegalArgumentException("Unknown selection in " + property + ": " + token);
            }
            selected.add(wildcard ? "*" : name);
        }
        return selected;
    }

    private static boolean selected(String value, List<String> selection) {
        return selection.contains("*") || selection.contains(value);
    }
}
