/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.xmlrpc;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.contract.types.TypeEchoService;
import net.hasor.example.contract.types.TypeEchoServiceImpl;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.example.contract.types.TypeValueVectors;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** 标准 XML-RPC 不支持宽整数；每个向量分别确认两端的明确范围拒绝。 */
@RunWith(Parameterized.class)
public class XmlRpcPythonTypeRejectionTest {
    private final String vector;

    public XmlRpcPythonTypeRejectionTest(String type, String vector) {
        this.vector = vector;
    }

    @Parameterized.Parameters(name = "types/python/xmlrpc/direct/{0}/{1}/rejection")
    public static Collection<Object[]> vectors() throws Exception {
        // 依据实际业务值选择标准 signed int32 无法表示的整数和 Map 成员。
        List<Object[]> parameters = new ArrayList<>();
        for (String vector : TypeValueVectors.names()) {
            TypeValue value = TypeValueVectors.value(vector);
            if (exceedsStandardIntegerRange(value)) {
                parameters.add(new Object[] { TypeValueVectors.type(vector), vector });
            }
        }
        return parameters;
    }

    @Test(timeout = 45000)
    public void pythonConsumerRejectsIntegerOutsideWireRange() throws Exception {
        // 发布真实 Meteor 业务服务；范围错误必须发生在 Python 标准库编码阶段。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("xmlrpc").option("allowNil", true).contextPath("/rpc");
        AtomicInteger invocations = new AtomicInteger();
        TypeEchoService implementation = request -> {
            invocations.incrementAndGet();
            return new TypeEchoServiceImpl().echo(request);
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TypeEchoService.class, implementation) //
                    .aliasName("xmlrpc", "types").protocol("xmlrpc").register();
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";

            // 对端只接受确切 OverflowError 和标准错误文本，业务方法不得被执行。
            try (XmlRpcTypePeerProcess peer = XmlRpcTypePeerProcess.rejectedConsumer(target, this.vector)) {
                peer.requireRangeRejection();
                assertEquals("Python must reject before sending the request", 0, invocations.get());
            }
        }
    }

    @Test(timeout = 45000)
    public void meteorConsumerRejectsIntegerOutsideWireRange() throws Exception {
        // 启动真实 Python 监听；Meteor 按公开 bootstrap 和服务订阅入口发起调用。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").protocol("xmlrpc").option("allowNil", true).contextPath("/rpc");
        try (XmlRpcTypePeerProcess peer = XmlRpcTypePeerProcess.provider(this.vector); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<TypeEchoService> service = client.publisher().rsfService(TypeEchoService.class) //
                    .aliasName("xmlrpc", "types").protocol("xmlrpc").register();
            InterAddress address = new InterAddress("xmlrpc", peer.host(), port, "default");
            MetClient remote = client.getRsfClient(address);
            TypeValue request = TypeValueVectors.value(this.vector);
            Class<?>[] types = { TypeValue.class };
            Object[] arguments = { request };

            // 精确校验协议编码的 signed int32 拒绝，不接受连接失败或等待超时。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                remote.syncInvoke(service, "echo", types, arguments);
            });
            assertTrue("Expected XML-RPC range IOException", failure.getCause() instanceof IOException);
            assertEquals("XML-RPC integer exceeds signed 32-bit range", failure.getCause().getMessage());
            peer.requireNoInvocation();
        }
    }

    private static boolean exceedsStandardIntegerRange(TypeValue value) {
        if (value.getInt64Value() < Integer.MIN_VALUE || value.getInt64Value() > Integer.MAX_VALUE) {
            return true;
        }
        BigInteger unsigned = value.getUnsigned64Value();
        if (unsigned.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            return true;
        }
        for (Long number : value.getMapValue().values()) {
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                return true;
            }
        }
        return false;
    }
}
