// Copyright 2015-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
namespace java net.hasor.example.contract.scalar.thrift.generated

enum ThriftScalarKind {
  RETAIL = 1,
  WHOLESALE = 2
}

struct ThriftScalarValue {
  1: required bool flag,
  2: required i64 integer,
  // Thrift has no float type. A Java float is widened exactly to double;
  // the bridge rejects double values that cannot narrow back without loss.
  3: required double single,
  4: required double decimal,
  5: required ThriftScalarKind kind,
  6: optional string note,
  7: required string receipt
}

struct ThriftScalarMoment {
  // java.util.Date uses native epoch milliseconds, including negative epochs.
  1: required i64 instantMillis,
  2: required string receipt
}

struct ThriftScalarDecimal {
  // BigDecimal.toString() preserves the exact decimal value and scale.
  1: required string amount,
  2: required string receipt
}

service ThriftScalarValueService {
  ThriftScalarValue echo(1: ThriftScalarValue request),
  ThriftScalarMoment echoMoment(1: ThriftScalarMoment request),
  ThriftScalarDecimal echoDecimal(1: ThriftScalarDecimal request)
}
