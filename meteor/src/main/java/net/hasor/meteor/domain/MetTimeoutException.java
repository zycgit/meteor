/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
/**
 *
 * @version : 2014年11月14日
 * @author 赵永春 (zyc@hasor.net)
 */
public class MetTimeoutException extends MetException {
    private static final long serialVersionUID = -445430836145251422L;

    public MetTimeoutException(String string) {
        super(ProtocolStatus.Timeout, string);
    }

    public MetTimeoutException(Throwable e) {
        super(ProtocolStatus.Timeout, e);
    }
}