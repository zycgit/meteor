/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.hasor.cobble.StringUtils;
import net.hasor.core.Inject;
import net.hasor.core.Singleton;
import net.hasor.meteor.console.component.commands.CommandDispatcher;
import net.hasor.meteor.console.dao.ClusterDao;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.console.model.vo.ClusterView;

/** 集群连接配置的测试、保存及凭据合并。 */
@Singleton
public final class ClusterService {
    @Inject
    private ClusterDao        clusterDao;
    @Inject
    private CommandDispatcher dispatcher;

    public List<ClusterView> list() {
        List<ClusterView> result = new ArrayList<>();
        for (ClusterProfile profile : this.clusterDao.list()) {
            result.add(new ClusterView(profile));
        }
        return result;
    }

    private ClusterProfile candidate(ClusterProfile profile) {
        if (StringUtils.isNotBlank(profile.getId())) {
            ClusterProfile previous = this.clusterDao.get(profile.getId());
            if (profile.getPassword() == null) {
                profile.setPassword(previous.getPassword());
            }
            if (profile.getManagementToken() == null) {
                profile.setManagementToken(previous.getManagementToken());
            }
        } else {
            profile.setId(UUID.randomUUID().toString());
        }
        profile.validate();
        return profile;
    }

    public void test(ClusterProfile profile) {
        this.dispatcher.test(this.candidate(profile));
    }

    public ClusterView save(ClusterProfile profile) throws IOException {
        this.test(profile);
        this.clusterDao.save(profile);
        this.dispatcher.invalidate(profile.getId());
        return new ClusterView(profile);
    }

    public void remove(String id) throws IOException {
        this.clusterDao.remove(id);
        this.dispatcher.invalidate(id);
    }
}
