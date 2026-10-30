package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/http"
	"time"

	"connectrpc.com/connect"
	"meteor.example/orders"
	ordersconnect "meteor.example/orders/connect"
)

type orderService struct{ store *orders.Store }

func (s *orderService) Save(
	_ context.Context, request *connect.Request[orders.Order],
) (*connect.Response[orders.Order], error) {
	return connect.NewResponse(s.store.Save(request.Msg)), nil
}

func (s *orderService) Find(
	_ context.Context, request *connect.Request[orders.OrderQuery],
) (*connect.Response[orders.Order], error) {
	found, err := s.store.Find(request.Msg.Id)
	if err != nil {
		return nil, connect.NewError(connect.CodeNotFound, err)
	}
	return connect.NewResponse(found), nil
}

func run() error {
	// Mount the generated Connect handler into an ordinary HTTP server.
	path, handler := ordersconnect.NewOrderDemoServiceHandler(&orderService{store: orders.NewStore()})
	mux := http.NewServeMux()
	mux.Handle("/connect"+path, http.StripPrefix("/connect", handler))
	listener, err := net.Listen("tcp", "127.0.0.1:8080")
	if err != nil {
		return err
	}
	defer listener.Close()
	server := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	defer server.Close()

	// Serve until this provider process is stopped.
	fmt.Println("Order provider is ready: http://127.0.0.1:8080/connect")
	return server.Serve(listener)
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}
