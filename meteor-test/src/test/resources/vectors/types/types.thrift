namespace java net.hasor.example.contract.types.thrift.generated
namespace go typecontract
namespace py typecontract

enum TypeKind {
  FIRST = 0,
  SECOND = 1
}

struct TypeRecord {
  1: i32 index,
  2: string name
}

struct TypeValue {
  1: string caseId,
  2: bool booleanValue,
  3: i8 int8Value,
  4: i16 int16Value,
  5: i32 int32Value,
  6: i64 int64Value,
  7: double float32Value,
  8: double float64Value,
  9: string textValue,
  10: binary binaryValue,
  11: TypeKind enumValue,
  12: list<string> listValue,
  13: map<string, i64> mapValue,
  14: TypeRecord recordValue,
  15: optional string optionalValue,
  16: string receipt,
  17: string unsigned64Value
}

service TypeEchoService {
  TypeValue echo(1: TypeValue request)
}
