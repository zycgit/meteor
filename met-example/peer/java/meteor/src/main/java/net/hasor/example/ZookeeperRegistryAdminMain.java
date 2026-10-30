/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.admin.MetClusterAdmin;
import net.hasor.meteor.registry.admin.MetDirectoryQuery;
import net.hasor.meteor.registry.admin.MetInstanceInfo;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;

/** 查询本地 ZooKeeper 的实例和服务目录。 */
public final class ZookeeperRegistryAdminMain {
    private ZookeeperRegistryAdminMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 创建独立管理会话，连接本地 ZooKeeper。
        RegistryConfiguration registry = new ZookeeperRegistryConfiguration().setAddress("127.0.0.1:2181") //
                .setManagementToken("demo-token");
        try (RegistryAdmin administration = registry.buildAdmin()) {
            MetClusterAdmin cluster = administration.getClusterAdmin();
            System.out.println("Registry provider: " + cluster.info().getProvider());

            // 分别查询实例与服务目录的第一页。
            for (MetInstanceInfo instance : cluster.instances(null, new MetDirectoryQuery()).getItems()) {
                System.out.println("Instance: " + instance.getInstanceId());
            }
            for (MetServiceInfo service : cluster.services(null, new MetDirectoryQuery()).getItems()) {
                System.out.println("Service: " + service.getServiceId());
            }
        }
    }
}
