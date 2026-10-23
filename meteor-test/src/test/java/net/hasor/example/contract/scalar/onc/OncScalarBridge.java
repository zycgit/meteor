/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.scalar.onc;
import net.hasor.example.contract.scalar.ScalarDecimal;
import net.hasor.example.contract.scalar.ScalarMoment;
import net.hasor.example.contract.scalar.ScalarValue;
import net.hasor.example.contract.scalar.ScalarValueService;

/** ONC 入口只转发调用，标量业务处理使用同一份共享实现。 */
public final class OncScalarBridge implements OncScalarService {
    private final ScalarValueService service;

    public OncScalarBridge(ScalarValueService service) {
        this.service = service;
    }

    @Override
    public ScalarValue echo(ScalarValue request) {
        return this.service.echo(request);
    }

    @Override
    public ScalarMoment echoMoment(ScalarMoment request) {
        return this.service.echoMoment(request);
    }

    @Override
    public ScalarDecimal echoDecimal(ScalarDecimal request) {
        return this.service.echoDecimal(request);
    }
}
