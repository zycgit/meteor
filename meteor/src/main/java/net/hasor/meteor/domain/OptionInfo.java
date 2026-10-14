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
import net.hasor.meteor.MetOptionParser;
import net.hasor.meteor.MetOptionSet;

/**
 *
 * @version : 2015年1月24日
 * @author 赵永春 (zyc@hasor.net)
 */
public class OptionInfo implements MetOptionSet {
    private static final Object              NULL      = new Object();
    private final        Map<Object, Object> optionMap = new ConcurrentHashMap<>();
    private              MetOptionSet        options;

    /** 在消息交付前设置同一次调用的选项来源，使后续补入的选项仍可读取。 */
    public void setOptions(MetOptionSet options) {
        this.options = options;
    }

    /**获取选项Key集合。*/
    public String[] getOptionKeys() {
        if (this.options != null) {
            return this.options.getOptionKeys();
        }
        Object[] keys = this.optionMap.keySet().toArray();
        String[] result = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            result[i] = keys[i] == NULL ? null : (String) keys[i];
        }
        return result;
    }

    /**获取选项数据*/
    public String getOption(String key) {
        if (this.options != null) {
            return this.options.getOption(key);
        }

        Object value = this.optionMap.get(key == null ? NULL : key);
        return value == NULL ? null : (String) value;
    }

    @Override
    public <T> T getOption(String key, MetOptionParser<T> parser) {
        return parser.parse(this.getOption(key));
    }

    /**设置选项数据*/
    public void addOption(String key, String value) {
        if (this.options != null) {
            this.options.addOption(key, value);
        } else {
            this.optionMap.put(key == null ? NULL : key, value == null ? NULL : value);
        }
    }

    @Override
    public <T> void addOption(String key, T value, MetOptionParser<T> parser) {
        this.addOption(key, parser.format(value));
    }

    /**删除选项数据*/
    public void removeOption(String key) {
        if (this.options != null) {
            this.options.removeOption(key);
        } else {
            this.optionMap.remove(key == null ? NULL : key);
        }
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