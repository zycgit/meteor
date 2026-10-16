/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.rpc.rsfs.support;

/** 三种已注册编码共用的普通业务接口。 */
public interface RsfsEcho {
    String echo(String value);
}
