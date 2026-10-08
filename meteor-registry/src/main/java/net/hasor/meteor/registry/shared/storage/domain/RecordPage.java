/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;
import java.util.Collections;
import java.util.List;

/** 一次存储查询的结果页；nextCursor 为空表示没有下一页。 */
public record RecordPage<T>(List<T> records, String nextCursor) {
    /** 接收本页独立的查询结果；调用方不再修改列表，对外仅提供只读视图。 */
    public RecordPage(List<T> records, String nextCursor) {
        this.records = Collections.unmodifiableList(records);
        this.nextCursor = nextCursor;
    }
}
