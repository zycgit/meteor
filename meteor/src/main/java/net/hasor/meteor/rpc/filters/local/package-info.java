/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
/**
 * RSF服务本地调用优先功能。
 * <p>场景：使用RSF框架发布了一个远程服务，但同时本地程序又基于RSF对该服务发起了远程调用。
 * <p>在这种场景下，本地调用优先功能会保证，所有外发的服务调用请求如果本地有服务提供者，那么远程调用改为本地调用。
 * 进而提升调用速度，而上层发起远程调用的应用并不知道真实调用来自本地。
 */
package net.hasor.meteor.rpc.filters.local;