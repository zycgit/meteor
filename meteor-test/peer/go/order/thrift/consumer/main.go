package main

import (
	"context"
	"log"
	"net/url"
	"os"
	"time"

	"github.com/apache/thrift/lib/go/thrift"
	orders "meteor.test/orderpeer/gen/orderpb"
	thriftorders "meteor.test/orderpeer/gen/thriftorder"
	"meteor.test/orderpeer/order"
)

func run() error {
	// Read the provider endpoint without coupling this consumer to its container address.
	targetAddress := os.Getenv("METEOR_ORDER_TARGET")
	if targetAddress == "" {
		targetAddress = "thrift://127.0.0.1:8080"
	}
	target, err := url.Parse(targetAddress)
	if err != nil {
		return err
	}

	// Open the official Thrift socket with the same framing and encoding as the provider.
	configuration := &thrift.TConfiguration{ConnectTimeout: 5 * time.Second, SocketTimeout: 10 * time.Second}
	socket := thrift.NewTSocketConf(target.Host, configuration)
	framing := thrift.NewTFramedTransportFactory(thrift.NewTTransportFactory())
	transport, err := framing.GetTransport(socket)
	if err != nil {
		return err
	}
	if err := transport.Open(); err != nil {
		return err
	}
	defer transport.Close()
	encoding := thrift.NewTBinaryProtocolFactory(true, true)
	client := thriftorders.NewOrderThriftServiceClientFactory(transport, encoding)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Save and find the same order through the generated client contract.
	sample := order.Sample()
	saved, err := client.Save(ctx, &thriftorders.ThriftOrder{
		ID: sample.Id, Description: &sample.Description, Attachment: sample.Attachment,
	})
	if err != nil {
		return err
	}
	found, err := client.Find(ctx, &thriftorders.ThriftOrderQuery{ID: sample.Id})
	if err != nil {
		return err
	}
	return order.Print(
		&orders.Order{Id: saved.ID, Description: saved.GetDescription(), Attachment: saved.Attachment},
		&orders.Order{Id: found.ID, Description: found.GetDescription(), Attachment: found.Attachment},
	)
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}
