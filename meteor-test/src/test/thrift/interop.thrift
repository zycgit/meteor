// Apache Thrift 0.25.0 interoperability contract; generated sources stay in test only.
namespace java net.hasor.example.protocol.thrift.generated

enum Shade {
  UNKNOWN = 0,
  RED = 1,
  BLUE = 2
}

union Choice {
  1: string text,
  2: i64 count,
  3: binary payload
}

struct Sample {
  1: i64 id,
  2: string text,
  3: bool enabled,
  4: i16 small,
  5: i32 count,
  6: double ratio,
  7: binary payload,
  8: list<string> tags,
  9: map<string, string> labels,
  10: set<i32> codes,
  11: i8 tiny,
  12: optional string note,
  13: optional i32 optionalCount,
  14: i32 defaultCount = 17,
  15: Shade shade = Shade.BLUE,
  16: optional Choice choice,
  17: optional uuid requestId
}

exception DeclaredFailure {
  1: i32 code,
  2: string reason
}

service InteropService {
  string echo(1: string value),
  // Declaration order deliberately differs from field ID order.
  i32 add(7: i32 left, 2: i32 right),
  Sample mirror(1: Sample value),
  list<string> echoList(1: list<string> value),
  map<string, i32> echoMap(1: map<string, i32> value),
  set<i64> echoSet(1: set<i64> value),
  binary echoBinary(1: binary value),
  Shade echoEnum(1: Shade value),
  Choice echoUnion(1: Choice value),
  uuid echoUuid(1: uuid value),
  Sample defaults(),
  void ping(),
  string fail(1: string reason) throws (1: DeclaredFailure failure),
  string crash(1: string reason),
  oneway void notify(1: string value),
  i32 notificationCount()
}

service ExtendedInteropService extends InteropService {
  string extended(1: string value)
}
