// Official peer contract matching the annotated OrderDemoService, Order and OrderQuery.
struct ThriftOrder {
  1: i32 id,
  2: optional string description,
  3: optional binary attachment
}

struct OrderQuery {
  1: i32 id
}

service OrderThriftService {
  ThriftOrder save(1: ThriftOrder order),
  ThriftOrder find(1: OrderQuery query)
}
