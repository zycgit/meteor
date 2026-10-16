/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import javax.xml.namespace.QName;

/** 携带版本无关 Fault 分类及错误处理阶段的协议输入异常。 */
final class SoapProtocolFaultException extends IllegalArgumentException {
    private final String  faultCode;
    private final boolean bodyFailure;
    private final QName   notUnderstood;

    private SoapProtocolFaultException(String faultCode, String message, boolean bodyFailure, QName notUnderstood) {
        super(message);
        this.faultCode = faultCode;
        this.bodyFailure = bodyFailure;
        this.notUnderstood = notUnderstood;
    }

    static SoapProtocolFaultException body(String faultCode, String message) {
        return new SoapProtocolFaultException(faultCode, message, true, null);
    }

    static SoapProtocolFaultException envelope(String faultCode, String message) {
        return new SoapProtocolFaultException(faultCode, message, false, null);
    }

    static SoapProtocolFaultException notUnderstood(QName header) {
        String message = "SOAP header is not understood: " + header;
        return new SoapProtocolFaultException("MustUnderstand", message, false, header);
    }

    String faultCode() {
        return this.faultCode;
    }

    boolean bodyFailure() {
        return this.bodyFailure;
    }

    QName notUnderstood() {
        return this.notUnderstood;
    }
}
