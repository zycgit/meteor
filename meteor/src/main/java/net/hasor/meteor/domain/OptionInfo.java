/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.meteor.MetOptionSet;

/**
 *
 * @version : 2015年1月24日
 * @author 赵永春 (zyc@hasor.net)
 */
public class OptionInfo implements MetOptionSet {
    // ConcurrentHashMap 不接受 null，使用内部标记保留空键和空值的行为。
    private static final Object              NULL      = new Object();
    private final        Map<Object, Object> optionMap = new ConcurrentHashMap<>();

    /**获取选项Key集合。*/
    public String[] getOptionKeys() {
        Object[] keys = this.optionMap.keySet().toArray();
        String[] result = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            result[i] = keys[i] == NULL ? null : (String) keys[i];
        }
        return result;
    }

    /**获取选项数据*/
    public String getOption(String key) {
        Object value = this.optionMap.get(key == null ? NULL : key);
        return value == NULL ? null : (String) value;
    }

    /**设置选项数据*/
    public void addOption(String key, String value) {
        this.optionMap.put(key == null ? NULL : key, value == null ? NULL : value);
    }

    /**删除选项数据*/
    public void removeOption(String key) {
        this.optionMap.remove(key == null ? NULL : key);
    }

    /** 逐项复制选项；源选项并发更新时，不保证整批数据属于同一时刻。 */
    public void addOptionMap(MetOptionSet optSet) {
        if (optSet == null) {
            return;
        }
        for (String key : optSet.getOptionKeys()) {
            this.addOption(key, optSet.getOption(key));
        }
    }
}