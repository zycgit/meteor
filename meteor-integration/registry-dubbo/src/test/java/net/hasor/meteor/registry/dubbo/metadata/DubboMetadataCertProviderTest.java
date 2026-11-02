/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.metadata;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.ssl.Cert;
import org.apache.dubbo.common.ssl.CertManager;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.metadata.MetadataServiceV2;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 验证元数据证书 SPI 的配置隔离与资源读取，不替代真实 TLS 握手测试。 */
public class DubboMetadataCertProviderTest {
    @TempDir
    Path files;

    @Test
    public void foreignApplicationDoesNotReceiveMetadataCertificates() {
        // 其他 Dubbo 应用即使使用相同参数名，也不属于本扩展的独占会话。
        FrameworkModel framework = new FrameworkModel();
        try {
            Map<String, String> parameters = Map.of("metadata.client-trust-cert-collection-path", "foreign.pem");
            ApplicationModel application = application(framework, parameters);
            URL address = metadataUrl(application, MetadataService.class.getName());

            // 不为外部模型启用 TLS，也不读取它的证书路径。
            DubboMetadataCertProvider provider = new DubboMetadataCertProvider();
            assertFalse(provider.isSupport(address));
            assertNull(provider.getConsumerConnectionConfig(address));
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void applicationWithoutConfigurationIsIgnored() {
        // SDK 内部模型尚未配置 application 时，证书扩展保持无操作。
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = framework.newApplication();
            URL address = metadataUrl(application, MetadataService.class.getName());
            assertFalse(new DubboMetadataCertProvider().isSupport(address));
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void businessCallsDoNotUseMetadataCertificates() {
        // 证书仅属于原生 MetadataService，不作为业务 RPC 的 TLS 配置。
        FrameworkModel framework = new FrameworkModel();
        try {
            Map<String, String> parameters = metadataParameters();
            parameters.put("metadata.client-trust-cert-collection-path", "unused.pem");
            ApplicationModel application = application(framework, parameters);
            URL business = metadataUrl(application, "net.hasor.example.DemoService");
            assertFalse(new DubboMetadataCertProvider().isSupport(business));
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void trustOnlyMaterialIsResolvedThroughNativeCertManager() throws Exception {
        // 单向认证只配置信任材料，不要求客户端持有自己的证书和私钥。
        File trust = this.material("trust.pem", "test-trust-material");
        FrameworkModel framework = new FrameworkModel();
        try {
            Map<String, String> parameters = metadataParameters();
            parameters.put("metadata.client-trust-cert-collection-path", trust.getAbsolutePath());
            ApplicationModel application = application(framework, parameters);
            URL address = metadataUrl(application, MetadataService.class.getName());

            // 通过 SDK 自身激活的 CertProvider 获取结果，验证资源注册和单向认证读取边界。
            CertManager manager = framework.getBeanFactory().getBean(CertManager.class);
            Cert certificate = manager.getConsumerConnectionConfig(address);
            assertNotNull(certificate);
            assertArrayEquals(Files.readAllBytes(trust.toPath()), certificate.getTrustCert());
            assertNull(certificate.getKeyCertChain());
            assertNull(certificate.getPrivateKey());
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void mutualTlsMaterialIncludesIdentityAndPassword() throws Exception {
        // 双向认证按应用快照读取信任材料、客户端证书、私钥与密码。
        File trust = this.material("trust.pem", "test-trust-material");
        File identity = this.material("client.pem", "test-client-material");
        File privateKey = this.material("client.key", "test-private-key-material");
        FrameworkModel framework = new FrameworkModel();
        try {
            Map<String, String> parameters = metadataParameters();
            parameters.put("metadata.client-trust-cert-collection-path", trust.getAbsolutePath());
            parameters.put("metadata.client-key-cert-chain-path", identity.getAbsolutePath());
            parameters.put("metadata.client-private-key-path", privateKey.getAbsolutePath());
            parameters.put("metadata.client-key-password", "test-password");
            ApplicationModel application = application(framework, parameters);
            URL address = metadataUrl(application, MetadataService.class.getName());

            // 这里只验证材料加载，协议场景负责实际握手及客户端身份认证。
            Cert certificate = new DubboMetadataCertProvider().getConsumerConnectionConfig(address);
            assertArrayEquals(Files.readAllBytes(identity.toPath()), certificate.getKeyCertChain());
            assertArrayEquals(Files.readAllBytes(privateKey.toPath()), certificate.getPrivateKey());
            assertArrayEquals(Files.readAllBytes(trust.toPath()), certificate.getTrustCert());
            assertEquals("test-password", certificate.getPassword());
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void fileUriMaterialIsResolved() throws Exception {
        // 用户也可以使用 file URI 指定本地 PEM 资源。
        File trust = this.material("trust.pem", "test-trust-material");
        FrameworkModel framework = new FrameworkModel();
        try {
            Map<String, String> parameters = metadataParameters();
            parameters.put("metadata.client-trust-cert-collection-path", trust.toURI().toString());
            ApplicationModel application = application(framework, parameters);
            URL address = metadataUrl(application, MetadataService.class.getName());
            Cert certificate = new DubboMetadataCertProvider().getConsumerConnectionConfig(address);
            assertArrayEquals(Files.readAllBytes(trust.toPath()), certificate.getTrustCert());
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void classpathMaterialIsResolved() {
        // Cobble 资源入口允许使用应用 classpath 内的材料。
        FrameworkModel framework = new FrameworkModel();
        try {
            Map<String, String> parameters = metadataParameters();
            parameters.put("metadata.client-trust-cert-collection-path", "metadata/material.txt");
            ApplicationModel application = application(framework, parameters);
            URL address = metadataUrl(application, MetadataService.class.getName());
            Cert certificate = new DubboMetadataCertProvider().getConsumerConnectionConfig(address);
            String content = new String(certificate.getTrustCert(), StandardCharsets.UTF_8);
            assertEquals("metadata-unit-test-material\n", content);
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void missingCertificateResourceReportsItsConfiguration() {
        // 无法读取明确配置的材料时保留错误，不退化为未认证连接。
        FrameworkModel framework = new FrameworkModel();
        try {
            Map<String, String> parameters = metadataParameters();
            parameters.put("metadata.client-trust-cert-collection-path", "/missing/metadata-trust.pem");
            ApplicationModel application = application(framework, parameters);
            URL address = metadataUrl(application, MetadataService.class.getName());
            DubboMetadataCertProvider provider = new DubboMetadataCertProvider();

            // 配置故障在建立 TLS 上下文前可辨识。
            Executable loadCertificate = () -> provider.getConsumerConnectionConfig(address);
            IllegalStateException failure = assertThrows(IllegalStateException.class, loadCertificate);
            assertTrue(failure.getMessage().contains("Cannot load Dubbo metadata TLS material"));
            assertTrue(failure.getMessage().contains("/missing/metadata-trust.pem"));
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void metadataV2UsesTheSameApplicationScopedMaterial() {
        // SDK 使用新的元数据服务版本时仍复用同一配置边界。
        FrameworkModel framework = new FrameworkModel();
        try {
            Map<String, String> parameters = metadataParameters();
            parameters.put("metadata.client-trust-cert-collection-path", "unused.pem");
            ApplicationModel application = application(framework, parameters);
            URL address = metadataUrl(application, MetadataServiceV2.class.getName());
            assertTrue(new DubboMetadataCertProvider().isSupport(address));
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void absentMetadataTlsConfigurationDoesNotEnableTls() {
        // 仅安装注册扩展不会改变原生明文元数据连接。
        FrameworkModel framework = new FrameworkModel();
        try {
            ApplicationModel application = application(framework, metadataParameters());
            URL address = metadataUrl(application, MetadataService.class.getName());
            assertFalse(new DubboMetadataCertProvider().isSupport(address));
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void metadataClientNeverProvidesServerCertificates() {
        // 本扩展不向 SDK 注册服务端身份，也不额外开放元数据监听端口。
        DubboMetadataCertProvider provider = new DubboMetadataCertProvider();
        assertNull(provider.getProviderConnectionConfig(URL.valueOf("dubbo://127.0.0.1:20880")));
    }

    private static Map<String, String> metadataParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(DubboMetadataCertProvider.APPLICATION_MARKER, "true");
        return parameters;
    }

    private static ApplicationModel application(FrameworkModel framework, Map<String, String> parameters) {
        ApplicationModel application = framework.newApplication();
        ApplicationConfig config = new ApplicationConfig(application);
        config.setName("metadata-tls-test");
        config.setQosEnable(false);
        config.setParameters(parameters);
        application.getApplicationConfigManager().setApplication(config);
        return application;
    }

    private static URL metadataUrl(ApplicationModel application, String service) {
        return new URL("dubbo", "127.0.0.1", 20880, service).setScopeModel(application);
    }

    private File material(String name, String content) throws Exception {
        Path file = this.files.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }
}
