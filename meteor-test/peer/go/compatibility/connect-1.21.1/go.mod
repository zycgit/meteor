module meteor.test/compatibility/connect-1.21.1

go 1.26.0

require (
	connectrpc.com/connect v1.21.1
	meteor.test/orderpeer v0.0.0
)

require (
	google.golang.org/grpc v1.84.0 // indirect
	google.golang.org/protobuf v1.36.12 // indirect
)

replace meteor.test/orderpeer => ../..
