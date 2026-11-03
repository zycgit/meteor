package main

import (
	"context"
	"log"
	"net"
	"net/http"
	"os"
	"time"

	"connectrpc.com/connect"
	orders "meteor.test/orderpeer/gen/orderpb"
	ordersconnect "meteor.test/orderpeer/gen/orderpb/orderpbconnect"
	"meteor.test/orderpeer/order"
)

type orderService struct{ store *order.Store }

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
	path, handler := ordersconnect.NewOrderDemoServiceHandler(&orderService{store: order.NewStore()})
	mux := http.NewServeMux()
	mux.Handle("/connect"+path, http.StripPrefix("/connect", handler))
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
	server := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	defer server.Close()

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
	if err := order.Ready("http://" + address + "/connect"); err != nil {
		return err
	}
	return server.Serve(listener)
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}
