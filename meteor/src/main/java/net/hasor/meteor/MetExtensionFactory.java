/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;

/** 可复用的扩展工厂；运行资源由每次创建的扩展实例持有。 */
public interface MetExtensionFactory {
    /** 创建指定上下文独享的扩展实例；返回前失败时，工厂负责释放尚未交给上下文的资源。 */
    MetExtension create(MetContext context) throws Exception;
}
