/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.composition.http;
import net.hasor.cobble.StringUtils;

/** 为每个发布协议保留可观察的业务标识。 */
public final class HttpCompositionServiceImpl implements HttpCompositionService {
    private final String prefix;

    public HttpCompositionServiceImpl(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public String echo(String value) {
        if (StringUtils.equals("reject", value)) {
            throw new IllegalArgumentException("rejected by " + this.prefix);
        }
        return this.prefix + value;
    }
}
