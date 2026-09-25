/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address;
/**
 * 路由策略脚本接口
 * @version : 2015年12月3日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface RuleScriptEngine {
    <T> RuleScript<T> eval(String script);
}