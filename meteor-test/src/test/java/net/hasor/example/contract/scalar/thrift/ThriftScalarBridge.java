/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.scalar.thrift;
import java.math.BigDecimal;
import java.util.Date;
import net.hasor.example.contract.scalar.*;
import net.hasor.example.contract.scalar.thrift.generated.*;
import org.apache.thrift.TException;

/** IDL 只定义类型映射；响应内容和收据由共享业务服务决定。 */
public final class ThriftScalarBridge implements ThriftScalarValueService.Iface {
    private final ScalarValueService business;

    public ThriftScalarBridge(ScalarValueService business) {
        this.business = business;
    }

    @Override
    public ThriftScalarValue echo(ThriftScalarValue request) {
        return toThrift(this.business.echo(fromThrift(request)));
    }

    @Override
    public ThriftScalarMoment echoMoment(ThriftScalarMoment request) {
        return toThrift(this.business.echoMoment(fromThrift(request)));
    }

    @Override
    public ThriftScalarDecimal echoDecimal(ThriftScalarDecimal request) {
        return toThrift(this.business.echoDecimal(fromThrift(request)));
    }

    public static ScalarValueService remote(ThriftScalarValueService.Iface remote) {
        return new Client(remote);
    }

    private static ThriftScalarValue toThrift(ScalarValue source) {
        ThriftScalarValue result = new ThriftScalarValue();
        result.setFlag(source.isFlag());
        result.setInteger(source.getInteger());
        result.setSingle(source.getSingle());
        result.setDecimal(source.getDecimal());
        result.setKind(toThrift(source.getKind()));
        result.setNote(source.getNote());
        result.setReceipt(source.getReceipt());
        return result;
    }

    private static ScalarValue fromThrift(ThriftScalarValue source) {
        // double 是 IDL 的显式桥接类型，拒绝不能精确还原的 Java float 值。
        double wireSingle = source.getSingle();
        float single = (float) wireSingle;
        if (Double.compare((double) single, wireSingle) != 0) {
            throw new IllegalArgumentException("Thrift single value cannot narrow exactly to float");
        }
        ScalarValue result = new ScalarValue();
        result.setFlag(source.isFlag());
        result.setInteger(source.getInteger());
        result.setSingle(single);
        result.setDecimal(source.getDecimal());
        result.setKind(fromThrift(source.getKind()));
        result.setNote(source.isSetNote() ? source.getNote() : null);
        result.setReceipt(source.getReceipt());
        return result;
    }

    private static ThriftScalarMoment toThrift(ScalarMoment source) {
        ThriftScalarMoment result = new ThriftScalarMoment();
        result.setInstantMillis(source.getInstant().getTime());
        result.setReceipt(source.getReceipt());
        return result;
    }

    private static ScalarMoment fromThrift(ThriftScalarMoment source) {
        ScalarMoment result = new ScalarMoment();
        result.setInstant(new Date(source.getInstantMillis()));
        result.setReceipt(source.getReceipt());
        return result;
    }

    private static ThriftScalarDecimal toThrift(ScalarDecimal source) {
        ThriftScalarDecimal result = new ThriftScalarDecimal();
        result.setAmount(source.getAmount().toString());
        result.setReceipt(source.getReceipt());
        return result;
    }

    private static ScalarDecimal fromThrift(ThriftScalarDecimal source) {
        ScalarDecimal result = new ScalarDecimal();
        result.setAmount(new BigDecimal(source.getAmount()));
        result.setReceipt(source.getReceipt());
        return result;
    }

    private static ThriftScalarKind toThrift(ScalarKind source) {
        switch (source) {
            case RETAIL:
                return ThriftScalarKind.RETAIL;
            case WHOLESALE:
                return ThriftScalarKind.WHOLESALE;
            default:
                throw new IllegalArgumentException("Unknown scalar kind: " + source);
        }
    }

    private static ScalarKind fromThrift(ThriftScalarKind source) {
        switch (source) {
            case RETAIL:
                return ScalarKind.RETAIL;
            case WHOLESALE:
                return ScalarKind.WHOLESALE;
            default:
                throw new IllegalArgumentException("Unknown Thrift scalar kind: " + source);
        }
    }

    private static final class Client implements ScalarValueService {
        private final ThriftScalarValueService.Iface remote;

        private Client(ThriftScalarValueService.Iface remote) {
            this.remote = remote;
        }

        @Override
        public ScalarValue echo(ScalarValue request) {
            try {
                return fromThrift(this.remote.echo(toThrift(request)));
            } catch (TException failure) {
                throw new IllegalStateException("Thrift scalar call failed", failure);
            }
        }

        @Override
        public ScalarMoment echoMoment(ScalarMoment request) {
            try {
                return fromThrift(this.remote.echoMoment(toThrift(request)));
            } catch (TException failure) {
                throw new IllegalStateException("Thrift moment call failed", failure);
            }
        }

        @Override
        public ScalarDecimal echoDecimal(ScalarDecimal request) {
            try {
                return fromThrift(this.remote.echoDecimal(toThrift(request)));
            } catch (TException failure) {
                throw new IllegalStateException("Thrift decimal call failed", failure);
            }
        }
    }
}
