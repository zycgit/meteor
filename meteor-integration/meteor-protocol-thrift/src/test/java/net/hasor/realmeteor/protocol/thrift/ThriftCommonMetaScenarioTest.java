/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import net.hasor.example.protocol.thrift.mapping.CommonMetaEcho;
import net.hasor.example.protocol.thrift.mapping.Note;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.thrift.ThriftIdlResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

public class ThriftCommonMetaScenarioTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void fileConsumerCallsTheAnnotationProvider() throws Exception {
        // 提供者只发布普通注解接口，消费者绑定既有 IDL。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(CommonMetaEcho.class, note -> note).register();
            ThriftIdlResource resource = this.resource();
            MetBindInfo<CommonMetaEcho> service = client.publisher().rsfService(CommonMetaEcho.class) //
                    .bindResource("thrift", resource).register();

            // 方法名和参数编号在两个入口中对应同一个 Java 契约。
            CommonMetaEcho remote = client.getRsfClient(server.getListenAddress("thrift")).getRemote(service);
            Note input = new Note();
            input.setId(7);
            input.setText("annotation provider");
            Note result = remote.mirror(input);
            assertEquals(7, result.getId());
            assertEquals("annotation provider", result.getText());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void annotationConsumerCallsTheFileProvider() throws Exception {
        // 提供者使用文件定义，消费者只依赖同一份 Java 接口与公共注解。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(CommonMetaEcho.class, note -> note) //
                    .bindResource("thrift", this.resource()).register();
            MetBindInfo<CommonMetaEcho> service = client.publisher().rsfService(CommonMetaEcho.class).register();

            // DTO 仍为普通对象，编码器复用准备阶段生成的协议绑定。
            CommonMetaEcho remote = client.getRsfClient(server.getListenAddress("thrift")).getRemote(service);
            Note input = new Note();
            input.setId(9);
            input.setText("file provider");
            Note result = remote.mirror(input);
            assertEquals(9, result.getId());
            assertEquals("file provider", result.getText());
        }
    }

    private ThriftIdlResource resource() throws Exception {
        return new ThriftIdlResource(this.getClass().getResource("/thrift/common-meta.thrift")) //
                .selectService("CommonMetaEcho");
    }

    private MetConfiguration configuration(boolean listen) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        MetConfiguration.ConnectorSettings connector = configuration.connector("tcp");
        if (listen) {
            connector.bind("127.0.0.1", 0);
        }
        connector.protocol("thrift").option("encoding", "compact");
        return configuration;
    }
}
