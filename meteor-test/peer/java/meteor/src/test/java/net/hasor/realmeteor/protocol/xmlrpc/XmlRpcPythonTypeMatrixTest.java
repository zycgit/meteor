/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.xmlrpc;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.hasor.example.contract.types.TypeEchoService;
import net.hasor.example.contract.types.TypeEchoServiceImpl;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.example.contract.types.TypeValueVectors;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.matrix.types.TypeValueAssertions;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** Python 标准库与 Meteor 的结构类型互通；每个向量和调用方向独立报告。 */
@ParameterizedClass(autoCloseArguments = false, name = "types/python/xmlrpc/direct/{0}/{1}", quoteTextArguments = false)
@MethodSource("vectors")
@NxnRpc(name = "xmlrpc")
public class XmlRpcPythonTypeMatrixTest {
    private final String vector;

    public XmlRpcPythonTypeMatrixTest(String type, String vector) {
        this.vector = vector;
    }
    public static Collection<Object[]> vectors() throws Exception {
        // 标准 XML-RPC 整数为有符号 32 位；仅排除确实超出线上整数范围的向量。
        List<Object[]> parameters = new ArrayList<>();
        for (String vector : TypeValueVectors.names()) {
            TypeValue value = TypeValueVectors.value(vector);
            if (standardIntegerRange(value)) {
                parameters.add(new Object[] { TypeValueVectors.type(vector), vector });
            }
        }
        return parameters;
    }

    @Test
    @Timeout(value = 45000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void pythonConsumerCallsMeteorProvider() throws Exception {
        // 配置真实监听和显式 nil 扩展，普通 Java DTO 由协议直接编解码。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("xmlrpc").option("allowNil", true).contextPath("/rpc");
        try (MetContext server = configuration.buildContext()) {
            // 启动容器后发布共享接口，Python 使用 types.echo 标准方法名访问。
            server.start();
            server.publisher().rsfService(TypeEchoService.class, new TypeEchoServiceImpl()) //
                    .aliasName("xmlrpc", "types").protocol("xmlrpc").register();
            int port = server.getListenAddress("xmlrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";

            // 独立 Python 消费者验证本向量的原生类型、精确值和提供者业务收据。
            try (XmlRpcTypePeerProcess peer = XmlRpcTypePeerProcess.consumer(target, this.vector)) {
                peer.requireSuccess();
            }
        }
    }

    @Test
    @Timeout(value = 45000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerCallsPythonProvider() throws Exception {
        // Python 作为独立网络提供者；Meteor 只配置出站端点，不开放监听。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").protocol("xmlrpc").option("allowNil", true).contextPath("/rpc");
        try (XmlRpcTypePeerProcess peer = XmlRpcTypePeerProcess.provider(this.vector); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<TypeEchoService> service = client.publisher().rsfService(TypeEchoService.class) //
                    .aliasName("xmlrpc", "types").protocol("xmlrpc").register();
            InterAddress address = new InterAddress("xmlrpc", peer.host(), port, "default");
            TypeEchoService remote = client.getRsfClient(address).getRemote(service);

            // 使用共享真实 Java 类型发起 RPC，校验当前向量及 Python 产生的业务收据。
            TypeValue expected = TypeValueVectors.value(this.vector);
            TypeValue actual = remote.echo(expected);
            TypeValueAssertions.assertVector(this.vector, expected, actual);
        }
    }

    private static boolean standardIntegerRange(TypeValue value) {
        if (value.getInt64Value() < Integer.MIN_VALUE || value.getInt64Value() > Integer.MAX_VALUE) {
            return false;
        }
        BigInteger unsigned = value.getUnsigned64Value();
        if (unsigned.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            return false;
        }
        for (Long number : value.getMapValue().values()) {
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                return false;
            }
        }
        return true;
    }
}
