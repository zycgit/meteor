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
public class MetException extends RuntimeException {
    private static final long  serialVersionUID = -2959224725202940531L;
    private              short status           = ProtocolStatus.Unknown;

    public MetException(String string, Throwable e) {
        super(string, e);
    }

    public MetException(short status, String string) {
        super("(" + status + ") - " + string);
        this.status = status;
    }

    public MetException(short status, Throwable e) {
        this(status, e.getMessage(), e);
    }

    public MetException(short status, String string, Throwable e) {
        super("(" + status + ") - " + string, e);
        this.status = status;
    }

    public short getStatus() {
        return this.status;
    }
}