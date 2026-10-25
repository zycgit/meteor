package main

import (
	"context"
	"log"
	"net/http"
	"time"

	"connectrpc.com/connect"
	"meteor.example/orders"
	ordersconnect "meteor.example/orders/connect"
)

func run() error {
	// The generated client combines this base URL with the service and method names.
	transport := &http.Transport{}
	defer transport.CloseIdleConnections()
	client := ordersconnect.NewOrderDemoServiceClient(
		&http.Client{Transport: transport}, "http://127.0.0.1:8080/connect")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Save and query one order using the official Connect protocol and Protobuf encoding.
	order := orders.Sample()
	saved, err := client.Save(ctx, connect.NewRequest(order))
	if err != nil {
		return err
	}
	found, err := client.Find(ctx, connect.NewRequest(&orders.OrderQuery{Id: order.Id}))
	if err != nil {
		return err
	}
	return orders.Print(saved.Msg, found.Msg)
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}
