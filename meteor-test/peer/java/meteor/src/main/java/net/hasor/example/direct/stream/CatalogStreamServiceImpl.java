/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.direct.stream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.meteor.rpc.MetCaller;

/** 按消费需求生成商品流、汇总请求流或返回双向流。 */
final class CatalogStreamServiceImpl implements CatalogStreamService {
    public CatalogItem echo(CatalogItem item) {
        return item;
    }

    public Flow.Publisher<CatalogItem> list(CatalogItem query) {
        List<CatalogItem> items = new ArrayList<>();
        for (int index = 0; index < query.getCount(); index++) {
            items.add(this.item(query.getName(), index));
        }
        return MetCaller.fromIterable(items);
    }

    public CatalogItem sum(Flow.Publisher<CatalogItem> items) {
        AtomicInteger total = new AtomicInteger();
        try {
            MetCaller.consume(items, item -> total.addAndGet(item.getCount())).get(5, TimeUnit.SECONDS);
            return this.item("total", total.get());
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to consume items", failure);
        }
    }

    public Flow.Publisher<CatalogItem> exchange(Flow.Publisher<CatalogItem> items) {
        return items;
    }

    private CatalogItem item(String name, int count) {
        CatalogItem item = new CatalogItem();
        item.setName(name);
        item.setCount(count);
        return item;
    }
}
