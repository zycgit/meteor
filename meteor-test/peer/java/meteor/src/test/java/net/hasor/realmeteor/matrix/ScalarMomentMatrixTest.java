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

/** Date 始终按 UTC 绝对时刻断言，秒级 XML-RPC 只承担整秒向量。 */
@ParameterizedClass(autoCloseArguments = false, name = "moment/self/{0}/direct/{1}", quoteTextArguments = false)
@MethodSource("configurations")
@NxnRpc(name = "rsf", version = "1", cases = "moment/self/rsf-json/*")
@NxnRpc(name = "dubbo", cases = "moment/self/dubbo-hessian2/*")
@NxnRpc(name = "hprose", cases = "moment/self/hprose/*")
@NxnRpc(name = "hessian", version = "2", cases = "moment/self/hessian-2/*")
@NxnRpc(name = "jsonrpc", version = "2.0", cases = "moment/self/jsonrpc-json/*")
@NxnRpc(name = "xmlrpc", cases = "moment/self/xmlrpc-struct/*")
@NxnRpc(name = "soap", version = "1.1", cases = "moment/self/soap-literal/*")
@NxnRpc(name = "grpc", cases = "moment/self/grpc-custom-json/*")
@NxnRpc(name = "grpc", cases = "moment/self/grpc-protobuf/*")
@NxnRpc(name = "grpc-web", cases = "moment/self/grpc-web-custom-json/*")
@NxnRpc(name = "grpc-web", cases = "moment/self/grpc-web-protobuf/*")
@NxnRpc(name = "connect", version = "1", cases = "moment/self/connect-json/*")
@NxnRpc(name = "connect", version = "1", cases = "moment/self/connect-protobuf/*")
@NxnRpc(name = "triple", cases = "moment/self/triple-java-hessian2/*")
@NxnRpc(name = "triple", cases = "moment/self/triple-protobuf/*")
@NxnRpc(name = "thrift", cases = "moment/self/thrift-binary-framed/*")
@NxnRpc(name = "oncrpc", version = "2", cases = "moment/self/oncrpc-xdr/*")
public class ScalarMomentMatrixTest {
    @TempDir
    public Path data;
    private final ScalarValueProfile profile;
    private final String             vector;

    public ScalarMomentMatrixTest(ScalarValueProfile profile, String vector) {
        this.profile = profile;
        this.vector = vector;
    }
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
        if (result.isEmpty()) {
            throw new IllegalArgumentException("The requested moment protocol/profile matrix has no matching cases");
        }
        return result;
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void utcInstantRetainsItsDeclaredPrecision() throws Exception {
        // 仅准备配置与独立数据目录，日期不会在启动前被格式化为业务字符串。
        String scope = "moment-" + UUID.randomUUID();
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
