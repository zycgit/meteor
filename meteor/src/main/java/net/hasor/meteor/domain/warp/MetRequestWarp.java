/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.warp;
import net.hasor.meteor.MetRequest;

/**
 * {@link MetRequest}接口包装器。
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public class MetRequestWarp extends AbstractMetRequestWarp {
    private final MetRequest request;

    public MetRequestWarp(MetRequest request) {
        this.request = request;
    }

    @Override
    protected MetRequest getRsfRequest() {
        return this.request;
    }
}