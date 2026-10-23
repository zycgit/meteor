namespace java example.contract

const list<i32> INITIAL_VALUES = [1, 2]

struct WireRecord {
    7: required i32 remote_count
    2: optional string description = "initial"
    4: optional list<i32> values = INITIAL_VALUES
}

service BindingService {
    WireRecord Echo(3: WireRecord query)
}
