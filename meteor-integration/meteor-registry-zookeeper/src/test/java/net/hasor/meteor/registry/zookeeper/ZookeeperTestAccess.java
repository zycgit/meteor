/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.zookeeper;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import org.apache.curator.framework.CuratorFramework;

/** 仅供测试向包内存储实现注入真实 SDK 会话，以观察断线及会话失效。 */
public final class ZookeeperTestAccess {
    private ZookeeperTestAccess() {
    }

    public static StorageAccess open(CuratorFramework client, String root) throws Exception {
        return new ZookeeperRegistry(client, root);
    }
}
