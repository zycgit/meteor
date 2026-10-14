/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;

/** 一个 RPC 请求在 HTTP 批次中的位置。 */
record HprosePendingRequest(HproseBatch batch, HproseCall call, int index) {
}
