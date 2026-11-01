/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc.types;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcSample;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.oncrpc.OncRpcNativeProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** C 端没有超范围整数值；Meteor 在编码前明确拒绝，不能截断后当作成功互通。 */
@ParameterizedClass(autoCloseArguments = false, name = "{0}/{1}", quoteTextArguments = false)
@MethodSource("parameters")
@NxnRpc(name = "oncrpc", version = "2")
public class OncRpcRejectedTypeMatrixTest {
    private final String transport;
    private final String vector;
    private final String error;

    public OncRpcRejectedTypeMatrixTest(String transport, String vector, String error) {
        this.transport = transport;
        this.vector = vector;
        this.error = error;
    }
    public static Collection<Object[]> parameters() {
        // 固定顺序对应当前 IDL 的范围和长度约束，不混入正常值场景。
        List<Object[]> parameters = new ArrayList<>();
        for (String transport : List.of("tcp", "udp")) {
            String integerRange = "XDR unsigned integer is outside its 32-bit range";
            parameters.add(new Object[] { transport, "u32-negative", integerRange });
            parameters.add(new Object[] { transport, "u32-overflow", integerRange });
            String hyperRange = "XDR unsigned hyper integer is outside its 64-bit range";
            parameters.add(new Object[] { transport, "u64-negative", hyperRange });
            parameters.add(new Object[] { transport, "u64-overflow", hyperRange });
            parameters.add(new Object[] { transport, "string-overflow", "XDR opaque length exceeds limit" });
            parameters.add(new Object[] { transport, "opaque-overflow", "XDR opaque length exceeds limit" });
            parameters.add(new Object[] { transport, "array-overflow", "XDR array length exceeds limit" });
            parameters.add(new Object[] { transport, "fixed-array-length", //
                    "XDR fixed array length does not match its declaration" });
        }
        return parameters;
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerRejectsUnrepresentableType() throws Exception {
        // 实际原生服务已就绪；错误只允许来自当前字段的确定编码约束。
        String protocol = "oncrpc";
        if (StringUtils.equals(this.transport, "udp")) {
            protocol = "oncrpc-udp";
        }
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(protocol);
        configuration.setQueueMinPoolSize(2).setDefaultTimeout(5000);
        configuration.connector(this.transport).protocol(protocol);
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=" + this.transport, "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource(protocol, OncRpcDemoCodecs.resource()).register();
            InterAddress address = new InterAddress(protocol, peer.host(), port, client.getSettings().getUnitName());
            MetClient caller = client.getRsfClient(address);

            // 每次只替换一个字段，精确核对 IllegalArgumentException，超时和网络失败不能通过。
            OncRpcSample argument = this.invalidSample();
            Class<?>[] types = { OncRpcSample.class };
            Object[] arguments = { argument };
            ExecutionException failure = assertThrows(ExecutionException.class, //
                    () -> caller.syncInvoke(service, "mirror", types, arguments));
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertTrue(cause instanceof IllegalArgumentException);
            assertEquals(this.error, cause.getMessage());
        }
    }

    private OncRpcSample invalidSample() {
        OncRpcSample value = OncRpcTypeVectors.sample("i32-zero");
        switch (this.vector) {
            case "u32-negative":
                value.setUnsignedValue(-1);
                break;
            case "u32-overflow":
                value.setUnsignedValue(0x100000000L);
                break;
            case "u64-negative":
                value.setUnsignedHyper(BigInteger.valueOf(-1));
                break;
            case "u64-overflow":
                value.setUnsignedHyper(BigInteger.ONE.shiftLeft(64));
                break;
            case "string-overflow":
                value.setText("x".repeat(4097));
                break;
            case "opaque-overflow":
                value.setBytes(new byte[65537]);
                break;
            case "array-overflow":
                value.setNumbers(Collections.nCopies(129, 0));
                break;
            case "fixed-array-length":
                value.setFixedNumbers(List.of(0, 0));
                break;
            default:
                throw new IllegalArgumentException("Unknown rejected C vector: " + this.vector);
        }
        return value;
    }
}
