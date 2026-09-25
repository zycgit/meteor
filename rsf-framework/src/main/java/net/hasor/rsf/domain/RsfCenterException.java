/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.domain;
/**
 *
 * @version : 2014年11月14日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RsfCenterException extends RsfException {
    private static final long serialVersionUID = 6625965825562561251L;

    public RsfCenterException(String string) {
        super(ProtocolStatus.InvokeError, string);
    }

    public RsfCenterException(Throwable e) {
        super(ProtocolStatus.InvokeError, e);
    }
}