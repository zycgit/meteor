/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.peer.application;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.admin.MetClusterAdmin;
import net.hasor.meteor.registry.admin.MetDirectoryQuery;
import net.hasor.meteor.registry.admin.MetInstanceInfo;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;

/** 测试自有管理端进程，通过公开管理 API 查询当前注册目录。 */
public final class RegistryAdministrationMain {
    private RegistryAdministrationMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 管理端独立建立存储会话，不借用提供者进程中的任何对象。
        RegistryConfiguration configuration = switch (arguments[0]) {
            case "nacos" -> new NacosRegistryConfiguration() //
                    .setAddress(arguments[1]).setGroup(arguments[2]);
            case "zookeeper" -> new ZookeeperRegistryConfiguration().setAddress(arguments[1]);
            default -> throw new IllegalArgumentException("Unknown registry: " + arguments[0]);
        };
        configuration.setManagementToken("demo-token");
        try (RegistryAdmin administration = configuration.buildAdmin()) {
            MetClusterAdmin cluster = administration.getClusterAdmin();
            System.out.println("Registry provider: " + cluster.info().getProvider());

            // 分别读取实例与服务，结果用于核对提供者的真实自动上报。
            for (MetInstanceInfo instance : cluster.instances(null, new MetDirectoryQuery()).getItems()) {
                System.out.println("Instance: " + instance.getInstanceId());
            }
            for (MetServiceInfo service : cluster.services(null, new MetDirectoryQuery()).getItems()) {
                System.out.println("Service: " + service.getServiceId());
            }
        }
    }
}
