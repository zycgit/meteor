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
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetService;

/** 程序号与过程号对应 protocol/x/scalar/values.x，业务签名由共享契约持有。 */
@MetService(id = 0x31234893L)
public interface OncScalarService extends ScalarValueService {
    @Override
    @MetMethod(id = 1)
    ScalarValue echo(ScalarValue request);

    @Override
    @MetMethod(id = 2)
    ScalarMoment echoMoment(ScalarMoment request);

    @Override
    @MetMethod(id = 3)
    ScalarDecimal echoDecimal(ScalarDecimal request);
}
