package main

import (
	"context"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"connectrpc.com/connect"
	orders "meteor.test/orderpeer/gen/orderpb"
	ordersconnect "meteor.test/orderpeer/gen/orderpb/orderpbconnect"
	"meteor.test/orderpeer/order"
)

func run() error {
	// Read the provider endpoint without coupling this consumer to its container address.
	targetAddress := os.Getenv("METEOR_ORDER_TARGET")
	if targetAddress == "" {
		targetAddress = "http://127.0.0.1:8080/connect"
	}
	target, err := url.Parse(targetAddress)
	if err != nil {
		return err
	}
	if target.Scheme == "connect" {
		target.Scheme = "http"
	}
	target.Path = strings.TrimRight(target.Path, "/")
	if target.Path == "" {
		target.Path = "/connect"
	}

	// The generated client combines this base URL with the service and method names.
	transport := &http.Transport{}
	defer transport.CloseIdleConnections()
	client := ordersconnect.NewOrderDemoServiceClient(
		&http.Client{Transport: transport}, target.String())
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Save and query one order using the official Connect protocol and Protobuf encoding.
	sample := order.Sample()
	saved, err := client.Save(ctx, connect.NewRequest(sample))
	if err != nil {
		return err
	}
	found, err := client.Find(ctx, connect.NewRequest(&orders.OrderQuery{Id: sample.Id}))
	if err != nil {
		return err
	}
	return order.Print(saved.Msg, found.Msg)
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}
