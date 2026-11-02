/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.metadata;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import net.hasor.cobble.ResourcesUtils;
import net.hasor.cobble.StringUtils;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.ssl.Cert;
import org.apache.dubbo.common.ssl.CertProvider;
import org.apache.dubbo.common.ssl.ProviderCert;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.SslConfig;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.metadata.MetadataServiceV2;

/** 仅为 Meteor 独占注册会话的原生 Dubbo 元数据 RPC 提供客户端证书。 */
@Activate(order = -10000)
public final class DubboMetadataCertProvider implements CertProvider {
    public static final String APPLICATION_MARKER = "meteor.registry.metadata-client";

    @Override
    public boolean isSupport(URL address) {
        ApplicationConfig application = address.getOrDefaultApplicationModel() //
                .getApplicationConfigManager().getApplication().orElse(null);
        if (application == null) {
            return false;
        }
        Map<String, String> parameters = application.getParameters();
        if (parameters == null || !StringUtils.equals("true", parameters.get(APPLICATION_MARKER))) {
            return false;
        }
        boolean metadataV1 = StringUtils.equals(MetadataService.class.getName(), address.getServiceInterface());
        boolean metadataV2 = StringUtils.equals(MetadataServiceV2.class.getName(), address.getServiceInterface());
        if (!metadataV1 && !metadataV2) {
            return false;
        }
        String trust = parameters.get("metadata." + SslConfig.CLIENT_TRUST_CERT_COLLECTION_PATH);
        String certificate = parameters.get("metadata." + SslConfig.CLIENT_KEY_CERT_CHAIN_PATH);
        return trust != null || certificate != null;
    }

    @Override
    public ProviderCert getProviderConnectionConfig(URL localAddress) {
        return null;
    }

    @Override
    public Cert getConsumerConnectionConfig(URL remoteAddress) {
        if (!this.isSupport(remoteAddress)) {
            return null;
        }

        // 单向认证只读取信任证书；双向认证再附带客户端证书和私钥。
        Map<String, String> parameters = remoteAddress.getOrDefaultApplicationModel() //
                .getCurrentConfig().getParameters();
        byte[] trust = this.read(parameters.get("metadata." + SslConfig.CLIENT_TRUST_CERT_COLLECTION_PATH));
        byte[] certificate = this.read(parameters.get("metadata." + SslConfig.CLIENT_KEY_CERT_CHAIN_PATH));
        byte[] privateKey = this.read(parameters.get("metadata." + SslConfig.CLIENT_PRIVATE_KEY_PATH));
        String password = parameters.get("metadata." + SslConfig.CLIENT_KEY_PASSWORD);
        return new Cert(certificate, privateKey, trust, password);
    }

    private byte[] read(String resource) {
        if (resource == null) {
            return null;
        }
        try {
            Path path = Path.of(resource);
            if (Files.isRegularFile(path)) {
                return Files.readAllBytes(path);
            }
            try (InputStream stream = ResourcesUtils.getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new IOException("Resource not found");
                }
                return stream.readAllBytes();
            }
        } catch (IOException | InvalidPathException failure) {
            throw new IllegalStateException("Cannot load Dubbo metadata TLS material: " + resource, failure);
        }
    }
}
