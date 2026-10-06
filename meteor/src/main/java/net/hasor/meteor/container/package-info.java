/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
/**
 * RSF服务框架中的，Bean容器、以及RsfBinder机制实现。
 * <p>
 * 服务和过滤器通过实例、Supplier 或公开无参构造函数显式注册。
 * 此模块不执行容器扫描、依赖注入或 AOP 装配。
 */
package net.hasor.meteor.container;
