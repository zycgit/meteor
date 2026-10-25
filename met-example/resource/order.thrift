// Apache Thrift contract matching the ordinary Java OrderDemoService interface.
namespace java net.hasor.example.thrift.sdk.generated
namespace go thrift
namespace py order

struct ThriftOrder {
  1: i32 id,
  2: optional string description,
  3: optional binary attachment
}

struct ThriftOrderQuery {
  1: i32 id
}

service OrderThriftService {
  ThriftOrder save(1: ThriftOrder order),
  ThriftOrder find(1: ThriftOrderQuery query)
}
