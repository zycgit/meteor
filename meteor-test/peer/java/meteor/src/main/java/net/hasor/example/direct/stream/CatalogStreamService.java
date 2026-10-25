/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.direct.stream;
import java.util.concurrent.Flow;

/** 同一服务展示 unary、服务端流、客户端流和双向流。 */
public interface CatalogStreamService {
    CatalogItem echo(CatalogItem item);

    Flow.Publisher<CatalogItem> list(CatalogItem query);

    CatalogItem sum(Flow.Publisher<CatalogItem> items);

    Flow.Publisher<CatalogItem> exchange(Flow.Publisher<CatalogItem> items);
}
