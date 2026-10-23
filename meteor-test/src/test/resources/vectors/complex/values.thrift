// Copyright 2015-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
namespace java net.hasor.example.contract.complex.thrift.generated

struct ThriftComplexCustomer {
  1: required string code,
  2: required string name,
  3: required bool verified,
  4: required double rating
}

struct ThriftComplexLine {
  1: required string sku,
  2: required i32 quantity,
  3: required double unitPrice
}

struct ThriftComplexDocument {
  1: required i32 documentId,
  2: required ThriftComplexCustomer customer,
  3: required list<ThriftComplexLine> lines,
  4: required list<string> route,
  5: required string receipt
}

struct ThriftComplexIndex {
  1: required i32 revision,
  2: required map<string, ThriftComplexLine> linesByCode,
  3: required map<string, i32> stockByCode,
  4: required string receipt
}

service ThriftComplexValueService {
  ThriftComplexDocument acceptDocument(1: ThriftComplexDocument request),
  ThriftComplexIndex acceptIndex(1: ThriftComplexIndex request)
}
