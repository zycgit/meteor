/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.node.NodeRpcPeer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 独立 Node SDK 版本分别验证三种 Thrift 编码及精确 64 位整数的双向兼容。 */
@NxnRpc(name = "thrift")
@ParameterizedClass(autoCloseArguments = false, name = "sdk/node/thrift/{0}@{1}/{2}-{4}-{5}/{3}", quoteTextArguments = false)
@MethodSource("versions")
public class NodeThriftSdkVersionScenarioTest {
    private final String profile;
    private final String direction;
    private final String encoding;
    private final String kind;

    public NodeThriftSdkVersionScenarioTest(String sdk, String version, String profile, String direction,
            String encoding, String kind) {
        this.profile = profile;
        this.direction = direction;
        this.encoding = encoding;
        this.kind = kind;
    }
    public static List<Object[]> versions() throws Exception {
        // SDK 身份来自实际安装清单；每个方向、编码、类型及整数边界都是独立 testcase。
        List<Object[]> cases = new ArrayList<>();
        for (Object[] profile : NodeRpcPeer.compatibilityCases("thrift")) {
            String version = (String) profile[1];
            for (String encoding : new String[] { "binary", "compact", "json" }) {
                add(cases, profile, encoding, "string");
                add(cases, profile, encoding, "i64-max");
                add(cases, profile, encoding, "i64-min");
                if (StringUtils.equals("0.25.0", version)) {
                    add(cases, profile, encoding, "bigint-max");
                    add(cases, profile, encoding, "bigint-min");
                }
            }
        }
        return cases;
    }

    private static void add(List<Object[]> cases, Object[] profile, String encoding, String kind) {
        cases.add(new Object[] { profile[0], profile[1], profile[2], profile[3], encoding, kind });
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void selectedSdkDirectionPreservesTheDeclaredValue() throws Exception {
        // 本实例只进行一次业务调用，版本和线格式不通过全局属性切换。
        if (StringUtils.equals("SDK-to-Meteor", this.direction)) {
            this.sdkCallsMeteor();
        } else {
            this.meteorCallsSdk();
        }
    }

    private void sdkCallsMeteor() throws Exception {
        // 公开 bootstrap 启动临时 TCP 监听，普通 Java 接口由公共注解自动生成 Thrift 定义。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            VersionEcho implementation = new VersionEcho() {
                @Override
                public String echo(String value) {
                    return value;
                }

                @Override
                public long echoI64(long value) {
                    return value;
                }
            };
            provider.publisher().rsfService(VersionEcho.class, implementation) //
                    .aliasName("thrift", "VersionEcho").protocol("thrift").register();

            // 当前版本官方 Client 编解码真实请求，并验证响应值及 BigInt 场景的 JS 类型。
            int port = provider.getListenAddress("thrift").getPort();
            String target = "thrift://" + ContainerProcess.runnerHost() + ":" + port;
            try (NodeRpcPeer peer = NodeRpcPeer.startVersion(this.profile, "consumer", target, //
                    this.encoding, this.sdkKind(), this.value())) {
                peer.requireSuccess();
            }
        }
    }

    private void meteorCallsSdk() throws Exception {
        // 官方 Processor 独占自己的端口，Meteor 只创建出站连接器及普通 Java 订阅。
        MetConfiguration configuration = this.configuration(false);
        try (NodeRpcPeer peer = NodeRpcPeer.startVersion(this.profile, "provider", "0.0.0.0", "0", //
                this.encoding, this.sdkKind()); //
             MetContext consumer = configuration.buildContext()) {
            int port = peer.awaitPort();
            consumer.start();
            MetBindInfo<VersionEcho> service = consumer.publisher().rsfService(VersionEcho.class) //
                    .aliasName("thrift", "VersionEcho").protocol("thrift").register();
            InterAddress target = new InterAddress("thrift", peer.host(), port, "default");
            VersionEcho remote = consumer.getRsfClient(target).getRemote(service);

            // 每个参数实例只验证一种返回值，不将 i64 先转成 double 或 JavaScript Number。
            if (StringUtils.equals("string", this.kind)) {
                String expected = this.value();
                assertEquals(expected, remote.echo(expected));
            } else {
                long expected = Long.parseLong(this.value());
                assertEquals(expected, remote.echoI64(expected));
            }
        }
    }

    private MetConfiguration configuration(boolean listening) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.setDefaultTimeout(10000);
        MetConfiguration.ConnectorSettings tcp = configuration.connector("tcp");
        if (listening) {
            tcp.bind(ContainerProcess.runnerHost(), 0);
        }
        tcp.protocol("thrift").option("encoding", this.encoding).option("framed", true);
        return configuration;
    }

    private String sdkKind() {
        if (this.kind.startsWith("bigint")) {
            return "bigint";
        }
        if (this.kind.startsWith("i64")) {
            return "i64";
        }
        return "string";
    }

    private String value() {
        if (StringUtils.equals("string", this.kind)) {
            return "Node Thrift ↔ Meteor 中文🙂";
        }
        if (this.kind.endsWith("-min")) {
            return Long.toString(Long.MIN_VALUE);
        }
        return Long.toString(Long.MAX_VALUE);
    }

    public interface VersionEcho {
        String echo(@MetParam(name = "value", id = 1) String value);

        long echoI64(@MetParam(name = "value", id = 1) long value);
    }
}
