package main

import (
	"context"
	"fmt"
	"log"
	"net"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"meteor.example/orders"
)

type orderService struct {
	orders.UnimplementedOrderDemoServiceServer
	store *orders.Store
}

func (s *orderService) Save(_ context.Context, order *orders.Order) (*orders.Order, error) {
	return s.store.Save(order), nil
}

func (s *orderService) Find(_ context.Context, query *orders.OrderQuery) (*orders.Order, error) {
	found, err := s.store.Find(query.Id)
	if err != nil {
		return nil, status.Error(codes.NotFound, err.Error())
	}
	return found, nil
}

func run() error {
	// Create the official gRPC server and publish the generated service contract.
	listener, err := net.Listen("tcp", "127.0.0.1:8080")
	if err != nil {
		return err
	}
	defer listener.Close()
	server := grpc.NewServer()
	service := &orderService{store: orders.NewStore()}
	description := orders.OrderDemoService_ServiceDesc
	description.ServiceName = "grpc/" + description.ServiceName
	server.RegisterService(&description, service)
	defer server.Stop()

	// Serve until this provider process is stopped.
	fmt.Println("Order provider is ready: grpc://127.0.0.1:8080/grpc")
	return server.Serve(listener)
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}
