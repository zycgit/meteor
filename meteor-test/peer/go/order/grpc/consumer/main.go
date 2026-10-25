package main

import (
	"context"
	"log"
	"net/url"
	"os"
	"strings"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	orders "meteor.test/orderpeer/gen/orderpb"
	"meteor.test/orderpeer/order"
)

func run() error {
	// Read the provider endpoint without coupling this consumer to its container address.
	targetAddress := os.Getenv("METEOR_ORDER_TARGET")
	if targetAddress == "" {
		targetAddress = "grpc://127.0.0.1:8080/grpc"
	}
	target, err := url.Parse(targetAddress)
	if err != nil {
		return err
	}
	contextPath := strings.TrimRight(target.Path, "/")
	if contextPath == "" {
		contextPath = "/grpc"
	}

	// Dial the official SDK and add the endpoint context before the generated method path.
	interceptor := func(ctx context.Context, method string, request, reply any,
		connection *grpc.ClientConn, invoke grpc.UnaryInvoker, options ...grpc.CallOption) error {
		return invoke(ctx, contextPath+method, request, reply, connection, options...)
	}
	connection, err := grpc.NewClient(target.Host, grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithUnaryInterceptor(interceptor))
	if err != nil {
		return err
	}
	defer connection.Close()
	client := orders.NewOrderDemoServiceClient(connection)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Save one order and fetch that same order through two ordinary RPC methods.
	sample := order.Sample()
	saved, err := client.Save(ctx, sample)
	if err != nil {
		return err
	}
	found, err := client.Find(ctx, &orders.OrderQuery{Id: sample.Id})
	if err != nil {
		return err
	}
	return order.Print(saved, found)
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}
