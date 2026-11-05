/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.web;
import java.util.Map;
import net.hasor.core.Inject;
import net.hasor.meteor.console.global.http.ConsoleRequest;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.console.service.ClusterService;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.web.Invoker;
import net.hasor.web.annotation.Get;
import net.hasor.web.annotation.HttpMethod;
import net.hasor.web.annotation.MappingTo;
import net.hasor.web.annotation.Post;

/** 本地保存的注册中心连接管理。 */
@MappingTo("/api")
public class ClusterController {
    @Inject
    private ClusterService clusters;

    @Get
    @MappingTo("/clusters")
    public Object list() {
        return this.clusters.list();
    }

    @Post
    @MappingTo("/clusters/test")
    public Object test(Invoker invoker) throws Exception {
        this.clusters.test(this.profile(invoker));
        return Map.of("success", true);
    }

    @Post
    @MappingTo("/clusters")
    public Object save(Invoker invoker) throws Exception {
        return this.clusters.save(this.profile(invoker));
    }

    @HttpMethod("DELETE")
    @MappingTo("/clusters")
    public Object remove(Invoker invoker) throws Exception {
        this.clusters.remove(ConsoleRequest.from(invoker).required("id"));
        return Map.of("success", true);
    }

    private ClusterProfile profile(Invoker invoker) throws Exception {
        return JsonUtils.treeToValue(ConsoleRequest.from(invoker).body(), ClusterProfile.class);
    }
}
