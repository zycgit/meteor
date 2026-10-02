/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
/**
 * RPC 的两条处理链，统一由 RsfCaller 协调。
 * 对外公开 RsfCaller、RsfClientImpl 和 RsfFilterProvider，其余类仅供包内实现使用。
 *
 * <p>出站通过 RsfCaller.createRequest 和 invoke 发起，执行过滤器、登记请求、
 * 建连发送并管理超时；入站消息由 RsfCaller.onMessage 接收。</p>
 * <p>收到请求时交给 RsfDispatcher 和 RsfInvocationTask 执行本地服务，
 * 并通过来源 Channel 回复；收到响应或失败时完成对应的出站调用。</p>
 */
package net.hasor.rsf.rpc;
