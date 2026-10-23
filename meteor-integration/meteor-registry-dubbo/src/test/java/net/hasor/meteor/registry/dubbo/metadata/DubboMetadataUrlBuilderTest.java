/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.metadata;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.registry.client.DefaultServiceInstance;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.registry.client.metadata.MetadataServiceURLBuilder;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.Test;
import static org.junit.Assert.*;

public class DubboMetadataUrlBuilderTest {
    @Test
    public void explicitMetadataTlsSelectsPeerHostAwareTransport() {
        // 官方构建器给出的主机、端口、版本和其他参数仍作为最终调用地址。
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = application(framework, true);
            URL original = metadataUrl(application).addParameter("timeout", 789);
            DubboMetadataUrlBuilder builder = new DubboMetadataUrlBuilder(instance -> List.of(original));
            ServiceInstance instance = new DefaultServiceInstance("native-provider", application);

            // 仅 client 参数选择带正确 peerHost 的 SDK 传输。
            URL selected = builder.build(instance).get(0);
            assertEquals("meteor-peer-host-netty", selected.getParameter("client"));
            assertEquals(789, selected.getParameter("timeout", 0));
            assertEquals(original.getHost(), selected.getHost());
            assertEquals(original.getPort(), selected.getPort());
            assertEquals(original.getServiceInterface(), selected.getServiceInterface());
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void foreignApplicationPreservesItsOwnTransportSelection() {
        // 另一个 SDK 应用不带 Meteor 私有会话标记。
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = application(framework, false);
            URL original = metadataUrl(application).addParameter("client", "custom-client");
            DubboMetadataUrlBuilder builder = new DubboMetadataUrlBuilder(instance -> List.of(original));
            ServiceInstance instance = new DefaultServiceInstance("native-provider", application);
            assertSame(original, builder.build(instance).get(0));
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void plaintextMetadataDoesNotSelectTlsTransport() {
        // 本会话没有配置元数据证书时继续使用官方明文路径。
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = application(framework, true);
            application.getCurrentConfig().getParameters().remove("metadata.client-trust-cert-collection-path");
            URL original = metadataUrl(application);
            DubboMetadataUrlBuilder builder = new DubboMetadataUrlBuilder(instance -> List.of(original));
            ServiceInstance instance = new DefaultServiceInstance("native-provider", application);
            assertSame(original, builder.build(instance).get(0));
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void nativeStandardBuilderIsDecoratedThroughSdkSpi() {
        // 真实 SDK standard 构建器仍负责解析提供者声明和设置内部 ModuleModel。
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = application(framework, true);
            DefaultServiceInstance instance = new DefaultServiceInstance("native-provider", application);
            instance.setHost("localhost");
            instance.setPort(20880);
            String parameters = "{\"protocol\":\"dubbo\",\"port\":\"20881\",\"version\":\"1.0.0\"}";
            instance.getMetadata().put("dubbo.metadata-service.url-params", parameters);

            // SPI wrapper 在标准地址已构建后追加 scoped client，不替换标准构建实现。
            MetadataServiceURLBuilder builder = application.getExtensionLoader(MetadataServiceURLBuilder.class) //
                    .getExtension("standard");
            URL selected = builder.build(instance).get(0);
            assertEquals("meteor-peer-host-netty", selected.getParameter("client"));
            assertEquals("localhost", selected.getHost());
            assertEquals(20881, selected.getPort());
            assertEquals(MetadataService.class.getName(), selected.getServiceInterface());
            assertSame(application, selected.getApplicationModel());
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void emptyDelegateResultRemainsEmpty() {
        // 构建器没有可用元数据端点时不制造默认端点。
        DubboMetadataUrlBuilder builder = new DubboMetadataUrlBuilder(instance -> List.of());
        assertTrue(builder.build(null).isEmpty());
    }

    private static ApplicationModel application(FrameworkModel framework, boolean ownSession) {
        ApplicationModel application = framework.newApplication();
        ApplicationConfig configuration = new ApplicationConfig(application);
        configuration.setName("metadata-url-test");
        configuration.setQosEnable(false);
        Map<String, String> parameters = new LinkedHashMap<>();
        if (ownSession) {
            parameters.put(DubboMetadataCertProvider.APPLICATION_MARKER, "true");
        }
        parameters.put("metadata.client-trust-cert-collection-path", "unused.pem");
        configuration.setParameters(parameters);
        application.getApplicationConfigManager().setApplication(configuration);
        return application;
    }

    private static URL metadataUrl(ApplicationModel application) {
        return new URL("dubbo", "localhost", 20880, MetadataService.class.getName()).setScopeModel(application);
    }
}
