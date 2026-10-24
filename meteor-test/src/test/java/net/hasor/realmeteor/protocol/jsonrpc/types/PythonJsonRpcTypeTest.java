/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc.types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.example.contract.types.TypeEchoService;
import net.hasor.example.contract.types.TypeEchoServiceImpl;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.example.contract.types.TypeValueVectors;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.matrix.types.ForeignTypePeer;
import net.hasor.realmeteor.matrix.types.TypeValueAssertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertEquals;

/** Python 标准库 JSON-RPC 两方向逐类型验证，保留精确整数而不经 Number 或文本中转。 */
@RunWith(Parameterized.class)
public class PythonJsonRpcTypeTest {
    @Rule
    public final  TemporaryFolder data = new TemporaryFolder();
    private final String          vector;

    public PythonJsonRpcTypeTest(String vector, String type) {
        this.vector = vector;
    }

    @Parameterized.Parameters(name = "types/python/jsonrpc/direct/{1}/{0}")
    public static Collection<Object[]> vectors() throws Exception {
        // 每种边界值独立运行，不把多个类型的成功或失败折叠进一个 case。
        List<Object[]> cases = new ArrayList<>();
        for (String vector : TypeValueVectors.names()) {
            cases.add(new Object[] { vector, TypeValueVectors.type(vector) });
        }
        return cases;
    }

    @Test(timeout = 40000)
    public void meteorProviderAcceptsOnePythonTypeVector() throws Exception {
        // 正式 bootstrap 启动 HTTP 挂载，再发布同一类型契约和业务实现。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.setDataHome(this.data.newFolder("provider").toPath());
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("jsonrpc").contextPath("/types");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TypeEchoService.class, new TypeEchoServiceImpl()) //
                    .aliasName("jsonrpc", "types").protocol("jsonrpc").register();

            // urllib/json 在独立 Python 容器内完成收发和真实 Python 类型断言。
            int port = provider.getListenAddress("jsonrpc").getPort();
            String endpoint = "http://" + ContainerProcess.runnerHost() + ":" + port + "/types";
            String marker = "OK";
            if (this.vector.endsWith("negative-zero")) {
                marker = "LIMIT negative-zero";
            }
            try (ForeignTypePeer peer = ForeignTypePeer.consumer("python", "jsonrpc", endpoint, this.vector)) {
                peer.requireSuccess(marker);
            }
        }
    }

    @Test(timeout = 40000)
    public void meteorConsumerAcceptsOnePythonTypeVector() throws Exception {
        // Python HTTP 服务自行监听，Meteor 通过公开订阅入口创建纯远程代理。
        try (ForeignTypePeer peer = ForeignTypePeer.provider("python", "jsonrpc")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
            configuration.setDataHome(this.data.newFolder("consumer").toPath());
            configuration.connector("http").protocol("jsonrpc").contextPath("/types");
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<TypeEchoService> info = consumer.publisher().rsfService(TypeEchoService.class) //
                        .aliasName("jsonrpc", "types").protocol("jsonrpc").register();
                InterAddress address = new InterAddress("jsonrpc", peer.host(), port, "default");
                TypeEchoService remote = consumer.getRsfClient(address).getRemote(info);

                // 逐字段断言保留 long/BigInteger/字节/结构真实类型，负零规范化另行标识。
                TypeValue expected = TypeValueVectors.value(this.vector);
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
}
