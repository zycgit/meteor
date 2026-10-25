module meteor.test/compatibility/grpc-1.85.0

go 1.26.0

replace meteor.test/orderpeer => ../..

require (
	connectrpc.com/connect v1.19.1 // indirect
	google.golang.org/grpc v1.85.0
	google.golang.org/protobuf v1.36.12 // indirect
	meteor.test/orderpeer v0.0.0
)
