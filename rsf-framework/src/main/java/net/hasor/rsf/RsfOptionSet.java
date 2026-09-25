/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf;
/**
 *
 * @version : 2014年11月30日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface RsfOptionSet {
    /**获取选项Key集合。*/
    String[] getOptionKeys();

    /**获取选项数据*/
    String getOption(String key);

    /**设置选项数据*/
    void addOption(String key, String value);

    /**删除选项数据*/
    void removeOption(String key);
}