/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.scalar;
import java.math.BigDecimal;
import java.util.Date;

/** 每个入口实际读取声明类型并创建响应，收据证明请求已经进入共享业务实现。 */
public class ScalarValueServiceImpl implements ScalarValueService {
    @Override
    public ScalarValue echo(ScalarValue request) {
        ScalarValue response = new ScalarValue();
        response.setFlag(request.isFlag());
        response.setInteger(request.getInteger());
        response.setSingle(request.getSingle());
        response.setDecimal(request.getDecimal());
        response.setKind(request.getKind());
        response.setNote(request.getNote());
        response.setReceipt("accepted:" + request.getKind().name());
        return response;
    }

    @Override
    public ScalarMoment echoMoment(ScalarMoment request) {
        ScalarMoment response = new ScalarMoment();
        response.setInstant(new Date(request.getInstant().getTime()));
        response.setReceipt("accepted:instant");
        return response;
    }

    @Override
    public ScalarDecimal echoDecimal(ScalarDecimal request) {
        ScalarDecimal response = new ScalarDecimal();
        response.setAmount(new BigDecimal(request.getAmount().toString()));
        response.setReceipt("accepted:amount");
        return response;
    }
}
