/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc.types;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import net.hasor.cobble.StringUtils;
import net.hasor.example.contract.types.TypeEchoService;
import net.hasor.example.contract.types.TypeEchoServiceImpl;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.example.contract.types.TypeValueVectors;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.jsonrpc.JsonRpcException;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.matrix.types.ForeignTypePeer;
import net.hasor.realmeteor.matrix.types.TypeValueAssertions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** Jayson 使用原生 Number；不安全整数和负零按真实限制验证，不使用文本伪装原生支持。 */
@ParameterizedClass(autoCloseArguments = false, name = "types/node/jsonrpc/direct/{1}/{0}", quoteTextArguments = false)
@MethodSource("vectors")
@NxnRpc(name = "jsonrpc", version = "2.0")
public class NodeJsonRpcTypeTest {
    @TempDir
    public Path data;
    private final String          vector;

    public NodeJsonRpcTypeTest(String vector, String type) {
        this.vector = vector;
    }
    public static Collection<Object[]> vectors() throws Exception {
        // 顺序沿用共享向量；每种边界和调用方向都有自己的 JUnit 身份。
        List<Object[]> cases = new ArrayList<>();
        for (String vector : TypeValueVectors.names()) {
            cases.add(new Object[] { vector, TypeValueVectors.type(vector) });
        }
        return cases;
    }

    @Test
    @Timeout(value = 40000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorProviderAcceptsOneNodeTypeVector() throws Exception {
        // bootstrap 启动后发布类型业务接口，JSON-RPC 通过 /types 正式挂载。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("jsonrpc").contextPath("/types");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TypeEchoService.class, new TypeEchoServiceImpl()) //
                    .aliasName("jsonrpc", "types").protocol("jsonrpc").register();

            // 官方 Jayson 发真实 HTTP 请求；限制实例报告 LIMIT，不能计为精确类型通过。
            int port = provider.getListenAddress("jsonrpc").getPort();
            String endpoint = "http://" + ContainerProcess.runnerHost() + ":" + port + "/types";
            String marker = "OK";
            if (this.unsafeInteger()) {
                marker = "LIMIT integer-precision";
            } else if (this.vector.endsWith("negative-zero")) {
                marker = "LIMIT negative-zero";
            }
            try (ForeignTypePeer peer = ForeignTypePeer.consumer("node", "jsonrpc", endpoint, this.vector)) {
                peer.requireSuccess(marker);
            }
        }
    }

    @Test
    @Timeout(value = 40000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerAcceptsOneNodeTypeVector() throws Exception {
        // 独立 Jayson 服务端提供类型接口，消费者从 bootstrap 创建纯出站通道。
        try (ForeignTypePeer peer = ForeignTypePeer.provider("node", "jsonrpc")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
            configuration.setDataHome(Files.createDirectory(this.data.resolve("consumer")));
            configuration.connector("http").protocol("jsonrpc").contextPath("/types");
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<TypeEchoService> info = consumer.publisher().rsfService(TypeEchoService.class) //
                        .aliasName("jsonrpc", "types").protocol("jsonrpc").register();
                InterAddress address = new InterAddress("jsonrpc", peer.host(), port, "default");
                MetClient client = consumer.getRsfClient(address);
                TypeValue expected = TypeValueVectors.value(this.vector);

                // 官方 SDK 不能恢复不安全整数，对端明确拒绝，保留可识别错误而非失真回显。
                if (this.unsafeInteger()) {
                    Class<?>[] types = { TypeValue.class };
                    Object[] arguments = { expected };
                    ExecutionException failure = assertThrows(ExecutionException.class, //
                            () -> client.syncInvoke(info, "echo", types, arguments));
                    assertTrue(failure.getCause() instanceof JsonRpcException);
                    JsonRpcException error = (JsonRpcException) failure.getCause();
                    assertEquals(-32064, error.getCode());
                    String message = "JavaScript Number cannot preserve integer precision: " + this.vector;
                    assertEquals(message, error.getMessage());
                    return;
                }

                // 普通向量验证精确类型，负零按现有 JSON 数字规范化行为单独断言。
                TypeEchoService remote = client.getRemote(info);
                TypeValue actual = remote.echo(expected);
                if (this.vector.endsWith("negative-zero")) {
                    assertEquals(this.vector, actual.getCaseId());
                    assertEquals("accepted:" + this.vector, actual.getReceipt());
                    if (StringUtils.equals("float32", TypeValueVectors.type(this.vector))) {
                        assertEquals(0, Float.floatToRawIntBits(actual.getFloat32Value()));
                    } else {
                        assertEquals(0L, Double.doubleToRawLongBits(actual.getFloat64Value()));
                    }
                } else {
                    TypeValueAssertions.assertVector(this.vector, expected, actual);
                }
            }
        }
    }

    private boolean unsafeInteger() throws Exception {
        TypeValue value = TypeValueVectors.value(this.vector);
        String type = TypeValueVectors.type(this.vector);
        long safe = 9007199254740991L;
        if (StringUtils.equals("int64", type)) {
            return value.getInt64Value() < -safe || value.getInt64Value() > safe;
        }
        if (StringUtils.equals("uint64", type)) {
            return value.getUnsigned64Value().compareTo(BigInteger.valueOf(safe)) > 0;
        }
        if (StringUtils.equals("map", type)) {
            for (long number : value.getMapValue().values()) {
                if (number < -safe || number > safe) {
                    return true;
                }
            }
        }
        return false;
    }
}
