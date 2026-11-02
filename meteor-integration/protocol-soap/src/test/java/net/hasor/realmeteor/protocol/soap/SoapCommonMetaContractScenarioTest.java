/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import net.hasor.example.protocol.soap.NamedEcho;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

public class SoapCommonMetaContractScenarioTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void fileConsumerCallsTheAnnotationProvider() throws Exception {
        // WSDL 使用 Echo/message/answer，Java 接口保留自己的方法与参数名称。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(NamedEcho.class, value -> "generated:" + value) //
                    .aliasName("soap", "NamedEcho").register();
            MetBindInfo<NamedEcho> service = client.publisher().rsfService(NamedEcho.class) //
                    .aliasName("soap", "NamedEcho").bindResource("soap", this.resource()).register();

            // 服务端生成的定义与消费者绑定的文件进入相同的 Java 映射链路。
            NamedEcho remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(service);
            assertEquals("generated:hello", remote.repeat("hello"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void annotationConsumerCallsTheFileProvider() throws Exception {
        // 提供者绑定 WSDL，消费者通过相同的公共注解生成契约。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(NamedEcho.class, value -> "file:" + value) //
                    .aliasName("soap", "NamedEcho").bindResource("soap", this.resource()).register();
            MetBindInfo<NamedEcho> service = client.publisher().rsfService(NamedEcho.class) //
                    .aliasName("soap", "NamedEcho").register();

            // 返回值名称由公共描述提供，Dispatcher 始终执行 Java repeat。
            NamedEcho remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(service);
            assertEquals("file:hello", remote.repeat("hello"));
        }
    }

    private SoapWsdlResource resource() throws Exception {
        return new SoapWsdlResource(this.getClass().getResource("/mapping/common-meta.wsdl"));
    }

    private MetConfiguration configuration(boolean listen) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        MetConfiguration.ConnectorSettings connector = configuration.connector("http");
        if (listen) {
            connector.bind("127.0.0.1", 0);
        }
        connector.protocol("soap").contextPath("/services");
        return configuration;
    }
}
