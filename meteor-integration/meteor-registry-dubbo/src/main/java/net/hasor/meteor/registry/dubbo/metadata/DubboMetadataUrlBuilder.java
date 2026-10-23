/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.metadata;
import java.util.ArrayList;
import java.util.List;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.registry.client.metadata.MetadataServiceURLBuilder;

/** 保留官方元数据 URL 构建，仅为本会话的 TLS 元数据调用选择主机名适配传输。 */
public final class DubboMetadataUrlBuilder implements MetadataServiceURLBuilder {
    private final MetadataServiceURLBuilder delegate;
    private final DubboMetadataCertProvider certificates = new DubboMetadataCertProvider();

    public DubboMetadataUrlBuilder(MetadataServiceURLBuilder delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<URL> build(ServiceInstance instance) {
        List<URL> urls = this.delegate.build(instance);
        List<URL> configured = new ArrayList<>();
        for (URL url : urls) {
            if (this.certificates.isSupport(url)) {
                configured.add(url.addParameter("client", DubboMetadataTransporter.NAME));
            } else {
                configured.add(url);
            }
        }
        return configured;
    }
}
