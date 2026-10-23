/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.metadata;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.metadata.report.MetadataReport;
import org.apache.dubbo.metadata.report.MetadataReportFactory;
import org.apache.dubbo.metadata.report.support.AbstractMetadataReportFactory;
import org.apache.dubbo.remoting.zookeeper.curator5.ZookeeperClientManager;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ScopeModelAware;

/** 保留官方元数据读写，仅隔离 Meteor 注册会话的 ZooKeeper 映射监听。 */
public final class DubboMetadataReportFactory extends AbstractMetadataReportFactory implements ScopeModelAware {
    private final MetadataReportFactory delegate;
    private       ApplicationModel      application;

    public DubboMetadataReportFactory(MetadataReportFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public void setApplicationModel(ApplicationModel application) {
        this.application = application;
    }

    @Override
    public MetadataReport getMetadataReport(URL address) {
        // 普通 Dubbo 应用及其他注册中心仍使用原工厂。
        ApplicationConfig configuration = this.application.getApplicationConfigManager() //
                .getApplication().orElse(null);
        Map<String, String> parameters = configuration == null ? null : configuration.getParameters();
        boolean ownApplication = parameters != null //
                && StringUtils.equals("true", parameters.get(DubboMetadataCertProvider.APPLICATION_MARKER));
        if (!ownApplication || !StringUtils.equalsIgnoreCase("zookeeper", address.getProtocol())) {
            return this.delegate.getMetadataReport(address);
        }
        return super.getMetadataReport(address);
    }

    @Override
    protected MetadataReport createMetadataReport(URL address) {
        ZookeeperClientManager clients = ZookeeperClientManager.getInstance(this.application);
        return new DubboZookeeperMetadataReport(address, clients);
    }

    @Override
    public void destroy() {
        try {
            super.destroy();
        } finally {
            this.delegate.destroy();
        }
    }
}
