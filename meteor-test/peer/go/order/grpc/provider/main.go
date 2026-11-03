package main

import (
	"context"
	"log"
	"net"
	"os"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	orders "meteor.test/orderpeer/gen/orderpb"
	"meteor.test/orderpeer/order"
)

type orderService struct {
	orders.UnimplementedOrderDemoServiceServer
	store *order.Store
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
	bindHost := os.Getenv("METEOR_ORDER_BIND_HOST")
	if bindHost == "" {
		bindHost = "127.0.0.1"
	}
	port := os.Getenv("METEOR_ORDER_PORT")
	if port == "" {
		port = "8080"
	}
	listener, err := net.Listen("tcp", net.JoinHostPort(bindHost, port))
	if err != nil {
		return err
	}
	defer listener.Close()
	server := grpc.NewServer()
	service := &orderService{store: order.NewStore()}
	description := orders.OrderDemoService_ServiceDesc
	description.ServiceName = "grpc/" + description.ServiceName
	server.RegisterService(&description, service)
	defer server.Stop()

	// Serve until this provider process is stopped.
	publishHost := os.Getenv("METEOR_ORDER_PUBLISH_HOST")
	if publishHost == "" {
		publishHost = os.Getenv("METEOR_TEST_PEER_HOST")
	}
	if publishHost == "" {
		publishHost = bindHost
	}
	_, actualPort, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		return err
	}
	address := net.JoinHostPort(publishHost, actualPort)
	if err := order.Ready("grpc://" + address + "/grpc"); err != nil {
		return err
	}
	return server.Serve(listener)
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}
