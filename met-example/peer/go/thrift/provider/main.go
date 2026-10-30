package main

import (
	"context"
	"fmt"
	"log"
	"time"

	"github.com/apache/thrift/lib/go/thrift"
	"meteor.example/orders"
	thriftorders "meteor.example/orders/thrift"
)

type orderService struct{ store *orders.Store }

func (s *orderService) Save(_ context.Context, order *thriftorders.ThriftOrder) (*thriftorders.ThriftOrder, error) {
	saved := s.store.Save(&orders.Order{
		Id: order.ID, Description: order.GetDescription(), Attachment: order.Attachment,
	})
	return &thriftorders.ThriftOrder{
		ID: saved.Id, Description: &saved.Description, Attachment: saved.Attachment,
	}, nil
}

func (s *orderService) Find(_ context.Context, query *thriftorders.ThriftOrderQuery) (*thriftorders.ThriftOrder, error) {
	found, err := s.store.Find(query.ID)
	if err != nil {
		return nil, thrift.NewTApplicationException(thrift.INTERNAL_ERROR, err.Error())
	}
	return &thriftorders.ThriftOrder{
		ID: found.Id, Description: &found.Description, Attachment: found.Attachment,
	}, nil
}

func run() error {
	// The generated processor delegates to this application's order implementation.
	socket, err := thrift.NewTServerSocketTimeout("127.0.0.1:8080", 10*time.Second)
	if err != nil {
		return err
	}
	if err := socket.Listen(); err != nil {
		return err
	}
	defer socket.Close()
	processor := thriftorders.NewOrderThriftServiceProcessor(&orderService{store: orders.NewStore()})
	framing := thrift.NewTFramedTransportFactory(thrift.NewTTransportFactory())
	encoding := thrift.NewTBinaryProtocolFactory(true, true)
	server := thrift.NewTSimpleServer4(processor, socket, framing, encoding)
	defer server.Stop()

	// This example uses standard binary encoding over framed TCP.
	fmt.Println("Order provider is ready: thrift://127.0.0.1:8080")
	return server.Serve()
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}
