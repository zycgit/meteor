struct WireRecord {
    7: required i32 remote_count;
    2: optional string description = 17;
    4: optional list<i32> values = [1, 2];
}
service Records {
    WireRecord Echo(3: WireRecord input);
}
