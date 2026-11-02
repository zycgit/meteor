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
import java.util.regex.Pattern;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetExtension;
import net.hasor.meteor.MetExtensionFactory;
import org.apache.dubbo.common.URL;

/** 原生 Dubbo 注册中心桥接配置，不使用 Meteor 注册中心的数据格式。 */
public final class DubboRegistryConfiguration implements MetExtensionFactory {
    private       String              address                    = null;
    private       String              username                   = null;
    private       String              password                   = null;
    private       String              publishHost                = null;
    private       String              secureProtocolScheme       = "dubbos";
    private       String              metadataTrustCertificate   = null;
    private       String              metadataClientCertificate  = null;
    private       String              metadataClientPrivateKey   = null;
    private       String              metadataPrivateKeyPassword = null;
    private       int                 timeoutMillis              = 5000;
    private       long                refreshMillis              = 1000;
    private final Map<String, String> parameters                 = new LinkedHashMap<>();

    /** 设置必填的原生注册中心 URL，例如 zookeeper://127.0.0.1:2181。 */
    public DubboRegistryConfiguration setAddress(String address) {
        this.address = address;
        return this;
    }

    /** 设置注册中心认证用户名，与 password 配套；不作为业务服务的认证信息。 */
    public DubboRegistryConfiguration setUsername(String username) {
        this.username = username;
        return this;
    }

    /** 设置注册中心认证密码。 */
    public DubboRegistryConfiguration setPassword(String password) {
        this.password = password;
        return this;
    }

    /** 设置服务发布地址，未设置时使用实际监听地址，不能发布通配监听地址。 */
    public DubboRegistryConfiguration setPublishHost(String publishHost) {
        this.publishHost = publishHost;
        return this;
    }

    /** 设置 ssl-enabled 提供者使用的本机出站 scheme，默认 dubbos，需配置对应 TLS 挂载。 */
    public DubboRegistryConfiguration setSecureProtocolScheme(String secureProtocolScheme) {
        this.secureProtocolScheme = secureProtocolScheme;
        return this;
    }

    /** 设置注册中心连接与引用准备超时，单位毫秒，默认 5000。 */
    public DubboRegistryConfiguration setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        return this;
    }

    /** 设置本地声明的周期核对间隔，单位毫秒，默认 1000；元信息变更在下一轮生效。 */
    public DubboRegistryConfiguration setRefreshMillis(long refreshMillis) {
        this.refreshMillis = refreshMillis;
        return this;
    }

    /** 设置 SDK 元数据 RPC 信任的 PEM CA 文件；业务连接仍使用 MetConfiguration 的 TLS 配置。 */
    public DubboRegistryConfiguration setMetadataTrustCertificate(String location) {
        this.metadataTrustCertificate = location;
        return this;
    }

    /** 设置 SDK 元数据 RPC 双向认证使用的 PEM 客户端证书链。 */
    public DubboRegistryConfiguration setMetadataClientCertificate(String location) {
        this.metadataClientCertificate = location;
        return this;
    }

    /** 设置 SDK 元数据 RPC 双向认证使用的 PEM 客户端私钥，与证书配套。 */
    public DubboRegistryConfiguration setMetadataClientPrivateKey(String location) {
        this.metadataClientPrivateKey = location;
        return this;
    }

    /** 设置 SDK 元数据 RPC 私钥密码；未设置时使用无密码私钥。 */
    public DubboRegistryConfiguration setMetadataPrivateKeyPassword(String password) {
        this.metadataPrivateKeyPassword = password;
        return this;
    }

    /** 设置原生注册中心参数，例如 group、backup、session 和 namespace。 */
    public DubboRegistryConfiguration setParameters(Map<String, String> parameters) {
        this.parameters.clear();
        if (parameters != null) {
            this.parameters.putAll(parameters);
        }
        return this;
    }

    @Override
    public MetExtension create(MetContext context) {
        if (context.getExtension(DubboRegistryClient.class) != null) {
            throw new IllegalStateException("Only one native Dubbo registry can be installed on a Meteor context");
        }

        // 固定配置快照，构建阶段不连接注册中心。
        URL registryUrl = this.registryUrl();
        DubboRegistryService service = new DubboRegistryService(context, registryUrl, //
                this.publishHost, this.secureProtocolScheme, this.timeoutMillis, this.refreshMillis);
        return new DubboRegistryExtension(service);
    }

    URL registryUrl() {
        if (StringUtils.isBlank(this.address)) {
            throw new IllegalArgumentException("Dubbo registry address is required");
        }
        URL url;
        try {
            url = URL.valueOf(this.address.trim());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Dubbo registry address must be an absolute registry URL", failure);
        }
        boolean missingAddress = StringUtils.isBlank(url.getProtocol()) || StringUtils.isBlank(url.getHost());
        if (missingAddress || url.getPort() <= 0 || url.getPort() > 65535) {
            throw new IllegalArgumentException("Dubbo registry address must include protocol, host and port");
        }
        boolean authenticationConfigured = this.username != null || this.password != null;
        if (authenticationConfigured && (StringUtils.isBlank(this.username) || this.password == null)) {
            throw new IllegalArgumentException("Dubbo registry authentication requires username and password");
        }
        if (this.timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if (this.refreshMillis <= 0) {
            throw new IllegalArgumentException("refreshMillis must be positive");
        }
        String secureScheme = this.secureProtocolScheme;
        boolean validSecureScheme = secureScheme != null && Pattern.matches("[A-Za-z][A-Za-z0-9+.-]*", secureScheme);
        if (!validSecureScheme) {
            throw new IllegalArgumentException("secureProtocolScheme must be a valid URI scheme");
        }
        if (this.username != null) {
            url = url.setUsername(this.username).setPassword(this.password);
        }
        boolean urlAuthentication = url.getUsername() != null || url.getPassword() != null;
        if (urlAuthentication && (StringUtils.isBlank(url.getUsername()) || url.getPassword() == null)) {
            throw new IllegalArgumentException("Dubbo registry authentication requires username and password");
        }
        url = url.addParameters(this.parameters).addParameter("timeout", this.timeoutMillis);
        return this.metadataTls(url);
    }

    private URL metadataTls(URL url) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("metadata.client-trust-cert-collection-path", this.metadataTrustCertificate);
        options.put("metadata.client-key-cert-chain-path", this.metadataClientCertificate);
        options.put("metadata.client-private-key-path", this.metadataClientPrivateKey);
        options.put("metadata.client-key-password", this.metadataPrivateKeyPassword);
        options.values().removeIf(value -> value == null);
        url = url.addParameters(options);

        String certificate = url.getParameter("metadata.client-key-cert-chain-path");
        String privateKey = url.getParameter("metadata.client-private-key-path");
        boolean identityConfigured = certificate != null || privateKey != null;
        if (identityConfigured && (StringUtils.isBlank(certificate) || StringUtils.isBlank(privateKey))) {
            throw new IllegalArgumentException("Metadata TLS requires both client certificate and private key");
        }
        String trust = url.getParameter("metadata.client-trust-cert-collection-path");
        if (trust != null && StringUtils.isBlank(trust)) {
            throw new IllegalArgumentException("Metadata TLS trust certificate must not be blank");
        }
        if (url.getParameter("metadata.client-key-password") != null && !identityConfigured) {
            throw new IllegalArgumentException("Metadata TLS private key password requires a client identity");
        }
        return url;
    }
}
