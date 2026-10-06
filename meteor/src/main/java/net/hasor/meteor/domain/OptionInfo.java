/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.hasor.meteor.MetOptionSet;

/**
 *
 * @version : 2015年1月24日
 * @author 赵永春 (zyc@hasor.net)
 */
public class OptionInfo implements MetOptionSet {
    private final Set<String>         optionKeys = new HashSet<>();
    private final Map<String, String> optionMap  = new HashMap<>();

    /**获取选项Key集合。*/
    public String[] getOptionKeys() {
        return this.optionKeys.toArray(new String[0]);
    }

    /**获取选项数据*/
    public String getOption(String key) {
        return this.optionMap.get(key);
    }

    /**设置选项数据*/
    public void addOption(String key, String value) {
        this.optionKeys.add(key);
        this.optionMap.put(key, value);
    }

    /**删除选项数据*/
    public void removeOption(String key) {
        this.optionKeys.remove(key);
        this.optionMap.remove(key);
    }

    public void addOptionMap(MetOptionSet optSet) {
        if (optSet == null) {
            return;
        }
        for (String key : optSet.getOptionKeys()) {
            this.addOption(key, optSet.getOption(key));
        }
    }
}