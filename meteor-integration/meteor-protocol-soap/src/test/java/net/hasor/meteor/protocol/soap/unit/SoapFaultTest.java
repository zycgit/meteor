/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.unit;
import javax.xml.namespace.QName;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.protocol.soap.SoapFault;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** SOAP Fault 公共构造入口的对象不变量。 */
public class SoapFaultTest {
    @Test
    public void statusFactoryCreatesAnEncodableReason() {
        // 协议适配器使用状态工厂创建 Fault，不再需要额外补写 Reason。
        SoapFault fault = SoapFault.create(ProtocolStatus.InvokeError, new QName("urn:test", "Failure"), "failed");

        // 工厂产物已经满足编码器的 Reason 要求。
        assertEquals(1, fault.getReasons().size());
        assertEquals("en", fault.getReasons().get(0).language());
        assertEquals("failed", fault.getReasons().get(0).text());
    }
}
