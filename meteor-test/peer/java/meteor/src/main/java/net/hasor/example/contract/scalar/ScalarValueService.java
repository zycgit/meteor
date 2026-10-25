/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.scalar;

public interface ScalarValueService {
    ScalarValue echo(ScalarValue request);

    ScalarMoment echoMoment(ScalarMoment request);

    ScalarDecimal echoDecimal(ScalarDecimal request);
}
