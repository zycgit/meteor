/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc.filters.online;
import net.hasor.meteor.MetFilter;
import net.hasor.meteor.MetFilterChain;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一旦下线，所有远程的连入请求都被回绝：Forbidden
 * @version : 2016年3月23日
 * @author 赵永春 (zyc@hasor.net)
 */
public class OnlineMetFilter implements MetFilter {
    protected Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public void doFilter(MetRequest request, MetResponse response, MetFilterChain chain) throws Throwable {
        if (!request.isLocal() && !request.getContext().isOnline()) {
            response.sendStatus(ProtocolStatus.Forbidden, "the service is not yet ready.");
            return;
        }
        chain.doFilter(request, response);
    }
}