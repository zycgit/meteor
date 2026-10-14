/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
/**
 *
 * @version : 2014年11月30日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface MetOptionSet {
    /**获取选项Key集合。*/
    String[] getOptionKeys();

    /**获取选项数据*/
    String getOption(String key);

    /** 使用指定解析器读取选项。 */
    <T> T getOption(String key, MetOptionParser<T> parser);

    /**设置选项数据*/
    void addOption(String key, String value);

    /** 使用指定解析器写入选项，底层仍保存字符串。 */
    <T> void addOption(String key, T value, MetOptionParser<T> parser);

    /**删除选项数据*/
    void removeOption(String key);
}