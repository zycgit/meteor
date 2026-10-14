/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;

/** 单个选项的解析扩展，格式与空值含义由扩展自行定义。 */
public interface MetOptionParser<T> {
    /** 将选项字符串解析为类型化值，选项不存在时传入 null。 */
    T parse(String value);

    /** 将类型化值转换为选项字符串。 */
    String format(T value);
}