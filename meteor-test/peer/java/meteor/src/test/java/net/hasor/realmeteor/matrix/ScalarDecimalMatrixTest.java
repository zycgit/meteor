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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.hasor.example.contract.scalar.*;
import net.hasor.meteor.MetContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** BigDecimal 使用精确声明类型；XML-RPC 没有对应任意精度线类型。 */
@ParameterizedClass(autoCloseArguments = false, name = "decimal/self/{0}/direct/{1}", quoteTextArguments = false)
@MethodSource("configurations")
@NxnRpc(name = "rsf", version = "1", cases = "decimal/self/rsf-json/*")
@NxnRpc(name = "dubbo", cases = "decimal/self/dubbo-hessian2/*")
@NxnRpc(name = "hprose", cases = "decimal/self/hprose/*")
@NxnRpc(name = "hessian", version = "2", cases = "decimal/self/hessian-2/*")
@NxnRpc(name = "jsonrpc", version = "2.0", cases = "decimal/self/jsonrpc-json/*")
@NxnRpc(name = "soap", version = "1.1", cases = "decimal/self/soap-literal/*")
@NxnRpc(name = "grpc", cases = "decimal/self/grpc-custom-json/*")
@NxnRpc(name = "grpc", cases = "decimal/self/grpc-protobuf/*")
@NxnRpc(name = "grpc-web", cases = "decimal/self/grpc-web-custom-json/*")
@NxnRpc(name = "grpc-web", cases = "decimal/self/grpc-web-protobuf/*")
@NxnRpc(name = "connect", version = "1", cases = "decimal/self/connect-json/*")
@NxnRpc(name = "connect", version = "1", cases = "decimal/self/connect-protobuf/*")
@NxnRpc(name = "triple", cases = "decimal/self/triple-java-hessian2/*")
@NxnRpc(name = "triple", cases = "decimal/self/triple-protobuf/*")
@NxnRpc(name = "thrift", cases = "decimal/self/thrift-binary-framed/*")
@NxnRpc(name = "oncrpc", version = "2", cases = "decimal/self/oncrpc-xdr/*")
public class ScalarDecimalMatrixTest {
    @TempDir
    public Path data;
    private final ScalarValueProfile profile;
    private final String             vector;

    public ScalarDecimalMatrixTest(ScalarValueProfile profile, String vector) {
        this.profile = profile;
        this.vector = vector;
    }
    public static Collection<Object[]> configurations() throws IOException {
        // 未给 XML-RPC 添加字符串适配器，其原生不支持范围明确记录在类型清单中。
        List<Object[]> result = new ArrayList<>();
        List<String> names = ScalarValueVectors.names("decimals");
        for (ScalarValueProfile profile : ScalarValueProfile.values()) {
            if (!ScalarValueMatrixTest.selected(profile) || profile.isXmlRpc()) {
                continue;
            }
            for (String name : names) {
                result.add(new Object[] { profile, name });
            }
        }
        if (result.isEmpty()) {
            String scope = "The requested decimal protocol/profile matrix has no matching cases";
            String reason = "XML-RPC does not define an arbitrary-precision decimal type";
            throw new IllegalArgumentException(scope + "; " + reason);
        }
        return result;
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void exactDecimalRetainsEveryDigitAndItsScale() throws Exception {
        // 每份精确小数向量都拥有独立业务实例与上下文，不通过浮点中转准备请求。
        String scope = "decimal-" + UUID.randomUUID();
        var incoming = this.profile.providerConfiguration().setDefaultGroup(scope);
        incoming.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        var outgoing = this.profile.consumerConfiguration().setDefaultGroup(scope);
        outgoing.setDataHome(Files.createDirectory(this.data.resolve("consumer")));
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
