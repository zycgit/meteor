package main

import (
	"context"
	"log"
	"time"

	"github.com/apache/thrift/lib/go/thrift"
	"meteor.example/orders"
	thriftorders "meteor.example/orders/thrift"
)

func run() error {
	// Open the official Thrift socket with the same framing and encoding as the provider.
	configuration := &thrift.TConfiguration{ConnectTimeout: 5 * time.Second, SocketTimeout: 10 * time.Second}
	socket := thrift.NewTSocketConf("127.0.0.1:8080", configuration)
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
	order := orders.Sample()
	saved, err := client.Save(ctx, &thriftorders.ThriftOrder{
		ID: order.Id, Description: &order.Description, Attachment: order.Attachment,
	})
	if err != nil {
		return err
	}
	found, err := client.Find(ctx, &thriftorders.ThriftOrderQuery{ID: order.Id})
	if err != nil {
		return err
	}
	return orders.Print(
		&orders.Order{Id: saved.ID, Description: saved.GetDescription(), Attachment: saved.Attachment},
		&orders.Order{Id: found.ID, Description: found.GetDescription(), Attachment: found.Attachment},
	)
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}
