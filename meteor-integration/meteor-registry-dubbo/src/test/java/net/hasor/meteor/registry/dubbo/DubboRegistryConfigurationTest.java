/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.dubbo.common.URL;
import org.junit.Test;
import static org.junit.Assert.*;

/** 注册中心参数在创建连接之前完成校验，不依赖本机注册中心。 */
public class DubboRegistryConfigurationTest {
    @Test
    public void addressIsRequired() {
        // 未填写地址时不猜测本机注册中心。
        DubboRegistryConfiguration configuration = new DubboRegistryConfiguration();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::registryUrl);
        assertEquals("Dubbo registry address is required", failure.getMessage());
    }

    @Test
    public void whitespaceAddressIsRejected() {
        // 空白地址与未配置地址具有相同含义。
        DubboRegistryConfiguration configuration = new DubboRegistryConfiguration().setAddress(" \t ");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void addressRequiresProtocol() {
        // 只有主机和端口不能确定使用哪一种原生注册中心。
        DubboRegistryConfiguration configuration = new DubboRegistryConfiguration().setAddress("registry.example:2181");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void addressRequiresHost() {
        // 协议名称不能代替明确的服务地址。
        DubboRegistryConfiguration configuration = new DubboRegistryConfiguration().setAddress("zookeeper://:2181");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void addressRequiresPort() {
        // 注册中心端口必须由用户配置。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setAddress("zookeeper://registry.example");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void zeroPortIsRejected() {
        // 服务发现目标不能使用操作系统分配监听端口的语义。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setAddress("zookeeper://registry.example:0");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void portAboveNetworkRangeIsRejected() {
        // TCP 端口上限在建立任何 SDK 客户端之前检查。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setAddress("zookeeper://registry.example:65536");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void malformedPortRetainsTheParsingCause() {
        // URL 无法解析时，保留原始错误以便定位配置问题。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setAddress("zookeeper://registry.example:not-a-port");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::registryUrl);
        assertEquals("Dubbo registry address must be an absolute registry URL", failure.getMessage());
        assertNotNull(failure.getCause());
    }

    @Test
    public void addressWhitespaceIsTrimmed() {
        // 配置文件中的首尾空格不进入 SDK 的主机和端口。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setAddress("  zookeeper://registry.example:2181  ");
        URL url = configuration.registryUrl();
        assertEquals("zookeeper", url.getProtocol());
        assertEquals("registry.example", url.getHost());
        assertEquals(2181, url.getPort());
    }

    @Test
    public void timeoutDefaultsToFiveSeconds() {
        // 未设置时，连接和引用准备使用配置声明的默认值。
        URL url = this.configuration().registryUrl();
        assertEquals(5000, url.getParameter("timeout", 0));
    }

    @Test
    public void explicitTimeoutOverridesRawParameters() {
        // 具名配置的 timeout 优先于原生参数中的同名值。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setParameters(Map.of("timeout", "99"));
        configuration.setTimeoutMillis(2500);
        assertEquals(2500, configuration.registryUrl().getParameter("timeout", 0));
    }

    @Test
    public void zeroTimeoutIsRejected() {
        // 等待上限必须大于零，避免无限等待或不明确的立即失败语义。
        DubboRegistryConfiguration configuration = this.configuration().setTimeoutMillis(0);
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void negativeTimeoutIsRejected() {
        // 负数不是可接受的超时配置。
        DubboRegistryConfiguration configuration = this.configuration().setTimeoutMillis(-1);
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void zeroRefreshIntervalIsRejected() {
        // 周期核对间隔必须大于零，不能退化为持续占用调度线程的循环。
        DubboRegistryConfiguration configuration = this.configuration().setRefreshMillis(0);
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void negativeRefreshIntervalIsRejected() {
        // 负数不表示关闭核对，必须在配置阶段明确拒绝。
        DubboRegistryConfiguration configuration = this.configuration().setRefreshMillis(-1);
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void usernameRequiresPassword() {
        // 认证信息不得只填写用户名。
        DubboRegistryConfiguration configuration = this.configuration().setUsername("meteor");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void passwordRequiresUsername() {
        // 认证信息不得只填写密码。
        DubboRegistryConfiguration configuration = this.configuration().setPassword("test-password");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void blankUsernameIsRejected() {
        // 有密码时，空白用户名仍视为缺少认证身份。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setUsername(" ").setPassword("test-password");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void configuredCredentialsBecomeRegistryUrlCredentials() {
        // 用户名密码只用于注册中心 URL，不进入业务协议参数。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setUsername("meteor").setPassword("test-password");

        // SDK URL 分别保存认证信息，普通参数不出现密码副本。
        URL url = configuration.registryUrl();
        assertEquals("meteor", url.getUsername());
        assertEquals("test-password", url.getPassword());
        assertNull(url.getParameter("password"));
    }

    @Test
    public void addressCredentialsArePreservedWithoutExplicitCredentials() {
        // 完整 URL 自带的认证信息无需重复填写。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setAddress("zookeeper://meteor:test-password@registry.example:2181");
        URL url = configuration.registryUrl();
        assertEquals("meteor", url.getUsername());
        assertEquals("test-password", url.getPassword());
    }

    @Test
    public void explicitCredentialsOverrideAddressCredentials() {
        // 具名认证配置覆盖地址中携带的旧值。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setAddress("zookeeper://old:old-password@registry.example:2181");
        configuration.setUsername("meteor").setPassword("test-password");
        URL url = configuration.registryUrl();
        assertEquals("meteor", url.getUsername());
        assertEquals("test-password", url.getPassword());
    }

    @Test
    public void addressUsernameRequiresPassword() {
        // 完整 URL 里的认证信息也必须成对填写。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setAddress("zookeeper://meteor@registry.example:2181");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void addressPasswordRequiresUsername() {
        // 不能通过在 URL 中省略用户名绕过配对校验。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setAddress("zookeeper://:test-password@registry.example:2181");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void metadataTrustCertificateAloneEnablesServerAuthentication() {
        // 元数据 RPC 可以只配置受信任 CA，不要求客户端身份。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setMetadataTrustCertificate("/test/certificates/ca.pem");

        // 信任配置传给 SDK 的专用参数，不修改业务 RPC TLS 参数。
        URL url = configuration.registryUrl();
        assertEquals("/test/certificates/ca.pem", url.getParameter("metadata.client-trust-cert-collection-path"));
        assertNull(url.getParameter("metadata.client-key-cert-chain-path"));
        assertNull(url.getParameter("ssl-enabled"));
    }

    @Test
    public void metadataClientIdentityPassesCertificateKeyAndPasswordToSdk() {
        // 双向认证携带一对客户端证书与私钥，密码可单独配置。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setMetadataTrustCertificate("/test/certificates/ca.pem");
        configuration.setMetadataClientCertificate("/test/certificates/client.pem");
        configuration.setMetadataClientPrivateKey("/test/certificates/client-key.pem");
        configuration.setMetadataPrivateKeyPassword("test-key-password");

        // 所有字段使用元数据 RPC 的专用 URL 参数名。
        URL url = configuration.registryUrl();
        assertEquals("/test/certificates/client.pem", url.getParameter("metadata.client-key-cert-chain-path"));
        assertEquals("/test/certificates/client-key.pem", url.getParameter("metadata.client-private-key-path"));
        assertEquals("test-key-password", url.getParameter("metadata.client-key-password"));
    }

    @Test
    public void metadataClientIdentityMayUseAnUnencryptedPrivateKey() {
        // 无密码 PEM 私钥不需要构造一个额外的空密码参数。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setMetadataClientCertificate("/test/certificates/client.pem");
        configuration.setMetadataClientPrivateKey("/test/certificates/client-key.pem");
        URL url = configuration.registryUrl();
        assertNull(url.getParameter("metadata.client-key-password"));
        assertEquals("/test/certificates/client-key.pem", url.getParameter("metadata.client-private-key-path"));
    }

    @Test
    public void metadataClientCertificateRequiresPrivateKey() {
        // 证书不能单独构成客户端身份。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setMetadataClientCertificate("/test/certificates/client.pem");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void metadataClientPrivateKeyRequiresCertificate() {
        // 私钥不能单独构成客户端身份。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setMetadataClientPrivateKey("/test/certificates/client-key.pem");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void metadataClientCertificateCannotBeBlank() {
        // 填写空白证书路径时仍视为缺少配套身份文件。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setMetadataClientCertificate(" ");
        configuration.setMetadataClientPrivateKey("/test/certificates/client-key.pem");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void metadataClientPrivateKeyCannotBeBlank() {
        // 空白私钥路径不能与有效证书组成身份。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setMetadataClientCertificate("/test/certificates/client.pem");
        configuration.setMetadataClientPrivateKey(" ");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void metadataPrivateKeyPasswordRequiresClientIdentity() {
        // 仅设置密码时立即报告身份配置缺失。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setMetadataPrivateKeyPassword("test-key-password");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void metadataTrustCertificateCannotBeBlank() {
        // 显式配置的信任文件路径不能为空白。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setMetadataTrustCertificate(" ");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void rawMetadataTrustCertificateUsesTheSameValidation() {
        // 通过原生参数填写空白信任路径也不能跳过校验。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setParameters(Map.of("metadata.client-trust-cert-collection-path", " "));
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void rawMetadataCertificateRequiresPrivateKey() {
        // 原生参数中的证书同样需要配套私钥。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setParameters(Map.of("metadata.client-key-cert-chain-path", "/test/certificates/client.pem"));
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void rawMetadataPrivateKeyRequiresCertificate() {
        // 原生参数中的私钥同样需要配套证书。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setParameters(Map.of("metadata.client-private-key-path", "/test/certificates/client-key.pem"));
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void rawMetadataPasswordRequiresClientIdentity() {
        // 原生参数中的私钥密码不能脱离客户端身份单独存在。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setParameters(Map.of("metadata.client-key-password", "test-key-password"));
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void typedMetadataTrustCertificateOverridesRawParameter() {
        // 具名 TLS 配置优先于原生参数中的旧路径。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setParameters(Map.of("metadata.client-trust-cert-collection-path", "/test/old-ca.pem"));
        configuration.setMetadataTrustCertificate("/test/current-ca.pem");
        URL url = configuration.registryUrl();
        assertEquals("/test/current-ca.pem", url.getParameter("metadata.client-trust-cert-collection-path"));
    }

    @Test
    public void metadataIdentityValidationUsesTheMergedParameters() {
        // 原生私钥参数与具名证书配置可以组成同一个有效身份。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setParameters(Map.of("metadata.client-private-key-path", "/test/certificates/client-key.pem"));
        configuration.setMetadataClientCertificate("/test/certificates/client.pem");
        URL url = configuration.registryUrl();
        assertEquals("/test/certificates/client.pem", url.getParameter("metadata.client-key-cert-chain-path"));
        assertEquals("/test/certificates/client-key.pem", url.getParameter("metadata.client-private-key-path"));
    }

    @Test
    public void missingSecureSchemeIsRejected() {
        // 安全地址转换不能产生没有 scheme 的业务目标。
        DubboRegistryConfiguration configuration = this.configuration().setSecureProtocolScheme(null);
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void blankSecureSchemeIsRejected() {
        // 空白内容不能作为 URI scheme。
        DubboRegistryConfiguration configuration = this.configuration().setSecureProtocolScheme(" ");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void secureSchemeMustStartWithALetter() {
        // URI scheme 首字符使用字母，避免构建无效的 InterAddress。
        DubboRegistryConfiguration configuration = this.configuration().setSecureProtocolScheme("1dubbo");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void secureSchemeRejectsUriSeparators() {
        // 配置只接收 scheme，不接收完整 URI。
        DubboRegistryConfiguration configuration = this.configuration().setSecureProtocolScheme("dubbos://");
        assertThrows(IllegalArgumentException.class, configuration::registryUrl);
    }

    @Test
    public void customSecureSchemeAcceptsUriSchemeCharacters() {
        // 自定义 TLS 挂载可以使用合法的加号、点和横线。
        DubboRegistryConfiguration configuration = this.configuration().setSecureProtocolScheme("dubbo+tls.v1-test");
        assertEquals("registry.example", configuration.registryUrl().getHost());
    }

    @Test
    public void rawParametersAreCopiedAtConfigurationTime() {
        // 外部 Map 后续改动不能悄悄修改已经配置的注册中心参数。
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("group", "meteor-registry");
        DubboRegistryConfiguration configuration = this.configuration().setParameters(parameters);
        parameters.put("group", "changed-outside");
        assertEquals("meteor-registry", configuration.registryUrl().getParameter("group"));
    }

    @Test
    public void replacingRawParametersDropsPreviousEntries() {
        // setParameters 是替换配置，不在旧配置上隐式合并。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setParameters(Map.of("group", "old-group"));
        configuration.setParameters(Map.of("namespace", "meteor-test"));
        URL url = configuration.registryUrl();
        assertNull(url.getParameter("group"));
        assertEquals("meteor-test", url.getParameter("namespace"));
    }

    @Test
    public void nullRawParametersClearConfiguredParameters() {
        // null 明确清除之前通过 Map 设置的额外参数。
        DubboRegistryConfiguration configuration = this.configuration();
        configuration.setParameters(Map.of("group", "old-group"));
        configuration.setParameters(null);
        assertNull(configuration.registryUrl().getParameter("group"));
    }

    private DubboRegistryConfiguration configuration() {
        return new DubboRegistryConfiguration().setAddress("zookeeper://registry.example:2181");
    }
}
