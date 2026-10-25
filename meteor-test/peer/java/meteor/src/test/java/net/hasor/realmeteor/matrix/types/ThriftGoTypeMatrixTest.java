/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix.types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
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
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.golang.TypePeerProcess;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertTrue;

/** Go 原生类型与 Java 业务类型双向互通，每项向量独立报告。 */
@RunWith(Parameterized.class)
public class ThriftGoTypeMatrixTest {
    @Rule
    public final  TemporaryFolder data = new TemporaryFolder();
    private final String          encoding;
    private final String          type;
    private final String          vector;

    public ThriftGoTypeMatrixTest(String encoding, String type, String vector) {
        this.encoding = encoding;
        this.type = type;
        this.vector = vector;
    }

    @Parameterized.Parameters(name = "types/go/thrift-{0}/direct/{1}/{2}")
    public static Collection<Object[]> configurations() throws Exception {
        // 编码维度和类型向量均逐项编号，不能将同一协议的多个类型合并成一个 testcase。
        List<Object[]> result = new ArrayList<>();
        for (String encoding : new String[] { "binary", "compact", "json" }) {
            for (String vector : TypeValueVectors.names()) {
                result.add(new Object[] { encoding, TypeValueVectors.type(vector), vector });
            }
        }
        return result;
    }

    @Test(timeout = 45000)
    public void goConsumerPreservesSelectedTypeOnMeteorProvider() throws Exception {
        // Meteor 经过公开 bootstrap 启动，服务只转换固定 IDL 类型，业务仍复用统一实现。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            this.publish(provider);

            // 官方 Go SDK 在独立容器中从共享向量恢复真实 Go 类型后发起 RPC。
            String target = provider.getListenAddress("thrift").getHostPort();
            try (TypePeerProcess peer = TypePeerProcess.start(this.profile(), "client", this.vector, target)) {
                JsonNode result = peer.awaitResult();
                assertTrue(this.type + ": " + result, result.path("ok").asBoolean());
            }
        }
    }

    @Test(timeout = 45000)
    public void meteorConsumerPreservesSelectedTypeOnGoProvider() throws Exception {
        // Go 官方 SDK 监听真实临时端口，Meteor 消费端仅声明 Thrift 出站能力。
        MetConfiguration configuration = this.configuration(false);
        try (TypePeerProcess peer = TypePeerProcess.start(this.profile(), "server", this.vector, null); //
             MetContext consumer = configuration.buildContext()) {
            String target = peer.awaitReady();
            consumer.start();
            MetBindInfo<?> service = this.subscribe(consumer);
            InterAddress address = new InterAddress("thrift://" + target + "/type_contract");

            // SDK 兼容入口返回固定 IDL 对象，桥接层恢复普通 Java 接口所声明的类型。
            var caller = consumer.getRsfClient(address);
            Iface remote = (Iface) new ThriftClient(caller).getRemote(service);
            TypeEchoService business = ThriftTypeBridge.remote(remote);
            TypeValue request = TypeValueVectors.value(this.vector);
            TypeValue actual = business.echo(request);

            // 当前方法只断言选中的类型语义，同时验证业务收据确由 Go 服务生成。
            TypeValueAssertions.assertVector(this.vector, request, actual);
        }
    }

    private MetConfiguration configuration(boolean listener) throws Exception {
        // 缓存和服务分组独占，容器端点使用 runner 的真实 DNS 名称。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultGroup("types-thrift-" + UUID.randomUUID());
        configuration.setUnitName("type_contract").setDefaultProtocol("thrift");
        configuration.setDataHome(this.data.newFolder(listener ? "provider" : "consumer").toPath());
        var connector = configuration.connector("tcp");
        if (listener) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        connector.protocol("thrift").option("encoding", this.encoding).option("framed", true);
        return configuration;
    }

    private MetBindInfo<?> publish(MetContext context) {
        ThriftTypeBridge implementation = new ThriftTypeBridge(new TypeEchoServiceImpl());
        return context.publisher().rsfService(Iface.class, implementation) //
                .name("meteor.types.TypeEchoService").aliasName("thrift", "TypeEchoService") //
                .protocol("thrift").serialize("thrift").timeout(5000) //
                .bindResource("thrift", this.resource()).register();
    }

    private MetBindInfo<?> subscribe(MetContext context) {
        return context.publisher().rsfService(Iface.class) //
                .name("meteor.types.TypeEchoService").aliasName("thrift", "TypeEchoService") //
                .protocol("thrift").serialize("thrift").timeout(5000) //
                .bindResource("thrift", this.resource()).register();
    }

    private ThriftServiceResource resource() {
        return new ThriftServiceResource(Iface.class.getDeclaringClass());
    }

    private String profile() {
        return "thrift-" + this.encoding + "-framed";
    }
}
