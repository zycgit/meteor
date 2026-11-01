/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift.python;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.hasor.example.contract.types.TypeEchoService;
import net.hasor.example.contract.types.TypeEchoServiceImpl;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.example.contract.types.TypeValueVectors;
import net.hasor.example.contract.types.thrift.ThriftTypeBridge;
import net.hasor.example.contract.types.thrift.generated.TypeEchoService.Iface;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import net.hasor.meteor.test.nxn.NxnRpc;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.python.PythonTypePeerProcess;
import net.hasor.realmeteor.matrix.types.TypeValueAssertions;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** Python Thrift 三种编码逐类型双向互通；float32 扩为 double，u64 显式映射为十进制字符串。 */
@ParameterizedClass(autoCloseArguments = false, name = "types/python/thrift-{0}/direct/{1}/{2}", quoteTextArguments = false)
@MethodSource("vectors")
@NxnRpc(name = "thrift")
public class PythonThriftTypeInteropTest {
    private static final String SERVICE = "TypeEchoService";
    private final        String encoding;
    private final        String vector;

    public PythonThriftTypeInteropTest(String encoding, String type, String vector) {
        this.encoding = encoding;
        this.vector = vector;
    }
    public static Collection<Object[]> vectors() throws Exception {
        // 编码与类型向量分别生成参数实例，不在单个 testcase 中循环执行不同类型场景。
        List<Object[]> parameters = new ArrayList<>();
        for (String encoding : List.of("binary", "compact", "json")) {
            for (String vector : TypeValueVectors.names()) {
                parameters.add(new Object[] { encoding, TypeValueVectors.type(vector), vector });
            }
        }
        return parameters;
    }

    @Test
    @Timeout(value = 45000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorProviderPreservesPythonNativeType() throws Exception {
        // Meteor 从公开 bootstrap 启动；固定 IDL 桥接 float32/u64，业务仍复用普通类型实现。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            ThriftTypeBridge implementation = new ThriftTypeBridge(new TypeEchoServiceImpl());
            provider.publisher().rsfService(Iface.class, implementation) //
                    .name(SERVICE).protocol("thrift").aliasName("thrift", SERVICE).serialize("thrift") //
                    .bindResource("thrift", this.resource()).register();

            // 官方 Python 生成结构承载真实 bool/int/float/bytes/容器，单次只断言当前向量。
            InterAddress address = provider.getListenAddress("thrift");
            try (PythonTypePeerProcess peer = PythonTypePeerProcess.consumer( //
                    "thrift", this.encoding, this.vector, address.getHost(), address.getPort())) {
                peer.awaitSuccess(this.vector);
            }
        }
    }

    @Test
    @Timeout(value = 45000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerPreservesPythonNativeType() throws Exception {
        // Python Processor 在独立容器提供服务，Meteor 只启用相同编码的出站连接。
        MetConfiguration configuration = this.configuration(false);
        try (PythonTypePeerProcess peer = PythonTypePeerProcess.provider("thrift", this.encoding, this.vector); //
             MetContext consumer = configuration.buildContext()) {
            int port = peer.awaitPort();
            consumer.start();
            MetBindInfo<Iface> service = consumer.publisher().rsfService(Iface.class) //
                    .name(SERVICE).protocol("thrift").aliasName("thrift", SERVICE).serialize("thrift") //
                    .bindResource("thrift", this.resource()).register();

            // 官方生成接口复用同一编解码，显式桥恢复 Java 业务类型后只断言当前向量。
            InterAddress address = new InterAddress("thrift", peer.host(), port, "default");
            Iface wire = new ThriftClient(consumer.getRsfClient(address)).getRemote(service);
            TypeEchoService remote = ThriftTypeBridge.remote(wire);
            TypeValue expected = TypeValueVectors.value(this.vector);
            TypeValue actual = remote.echo(expected);
            TypeValueAssertions.assertVector(this.vector, expected, actual);
        }
    }

    private ThriftServiceResource resource() {
        return new ThriftServiceResource(Iface.class.getDeclaringClass());
    }

    private MetConfiguration configuration(boolean listen) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.setDefaultTimeout(10000).setQueueMinPoolSize(2).setQueueMaxPoolSize(4);
        MetConfiguration.ConnectorSettings tcp = configuration.connector("tcp");
        if (listen) {
            tcp.bind(ContainerProcess.runnerHost(), 0);
        }
        tcp.protocol("thrift").option("encoding", this.encoding).option("framed", true);
        return configuration;
    }
}
