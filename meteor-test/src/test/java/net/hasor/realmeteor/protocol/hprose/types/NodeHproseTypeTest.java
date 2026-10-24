/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.types;
import java.math.BigInteger;
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

/** 官方 Node Hprose 与 Meteor 逐类型双向互通；文本长整数映射和负零限制单独记录。 */
@RunWith(Parameterized.class)
public class NodeHproseTypeTest {
    @Rule
    public final  TemporaryFolder data = new TemporaryFolder();
    private final String          vector;

    public NodeHproseTypeTest(String vector, String type) {
        this.vector = vector;
    }

    @Parameterized.Parameters(name = "types/node/hprose/direct/{1}/{0}")
    public static Collection<Object[]> vectors() throws Exception {
        // 固定顺序沿用共享契约，每个向量与调用方向均为独立 JUnit 实例。
        List<Object[]> cases = new ArrayList<>();
        for (String vector : TypeValueVectors.names()) {
            cases.add(new Object[] { vector, TypeValueVectors.type(vector) });
        }
        return cases;
    }

    @Test(timeout = 40000)
    public void meteorProviderAcceptsOneNodeTypeVector() throws Exception {
        // 从 bootstrap 启动提供者，再发布同一份类型业务接口。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
        configuration.setDataHome(this.data.newFolder("provider").toPath());
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("hprose").contextPath("/types");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TypeEchoService.class, new TypeEchoServiceImpl()) //
                    .aliasName("Hprose", "types").protocol("hprose").register();

            // 独立 Node 官方 SDK 发起调用，并在自己的进程内断言当前字段。
            int port = provider.getListenAddress("hprose").getPort();
            String endpoint = "http://" + ContainerProcess.runnerHost() + ":" + port + "/types";
            try (ForeignTypePeer peer = ForeignTypePeer.consumer("node", "hprose", endpoint, this.vector)) {
                peer.requireSuccess(this.expectedMarker());
            }
        }
    }

    @Test(timeout = 40000)
    public void meteorConsumerAcceptsOneNodeTypeVector() throws Exception {
        // 官方 Node 服务端独立拥有监听，Meteor 消费者只配置出站通道。
        try (ForeignTypePeer peer = ForeignTypePeer.provider("node", "hprose")) {
            int port = peer.awaitPort();
            MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(5000);
            configuration.setDataHome(this.data.newFolder("consumer").toPath());
            configuration.connector("http").protocol("hprose").contextPath("/types");
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<TypeEchoService> info = consumer.publisher().rsfService(TypeEchoService.class) //
                        .aliasName("Hprose", "types").protocol("hprose").register();
                InterAddress address = new InterAddress("hprose", peer.host(), port, "default");
                TypeEchoService remote = consumer.getRsfClient(address).getRemote(info);

                // 正常值逐位验证；Node 写出的负零规范化验证单独保留，不能计为符号保持。
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

    private String expectedMarker() throws Exception {
        if (this.vector.endsWith("negative-zero")) {
            return "LIMIT negative-zero";
        }
        TypeValue value = TypeValueVectors.value(this.vector);
        String type = TypeValueVectors.type(this.vector);
        long safe = 9007199254740991L;
        boolean unsafe = false;
        if (StringUtils.equals("int64", type)) {
            unsafe = value.getInt64Value() < -safe || value.getInt64Value() > safe;
        } else if (StringUtils.equals("uint64", type)) {
            unsafe = value.getUnsigned64Value().compareTo(BigInteger.valueOf(safe)) > 0;
        } else if (StringUtils.equals("map", type)) {
            for (long number : value.getMapValue().values()) {
                if (number < -safe || number > safe) {
                    unsafe = true;
                }
            }
        }
        if (unsafe) {
            return "MAPPED decimal-text";
        }
        return "OK";
    }
}
