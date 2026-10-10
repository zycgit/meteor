/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.meteor.registry.shared.storage.StorageDocument;

/**
 * 单个 CAS 配置文档中的分片；记录版本独立于分片版本。
 */
final class RecordBucket {
    private Map<String, StorageDocument> records = new LinkedHashMap<>();

    public Map<String, StorageDocument> getRecords() {
        return this.records;
    }

    public void setRecords(Map<String, StorageDocument> records) {
        this.records = records;
    }
}
