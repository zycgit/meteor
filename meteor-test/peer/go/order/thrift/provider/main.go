package main

import (
	"context"
	"log"
	"net"
	"os"
	"time"

	"github.com/apache/thrift/lib/go/thrift"
	orders "meteor.test/orderpeer/gen/orderpb"
	thriftorders "meteor.test/orderpeer/gen/thriftorder"
	"meteor.test/orderpeer/order"
)

type orderService struct{ store *order.Store }

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
	bindHost := os.Getenv("METEOR_ORDER_BIND_HOST")
	if bindHost == "" {
		bindHost = "127.0.0.1"
	}
	port := os.Getenv("METEOR_ORDER_PORT")
	if port == "" {
		port = "8080"
	}
	socket, err := thrift.NewTServerSocketTimeout(net.JoinHostPort(bindHost, port), 10*time.Second)
	if err != nil {
		return err
	}
	if err := socket.Listen(); err != nil {
		return err
	}
	defer socket.Close()
	processor := thriftorders.NewOrderThriftServiceProcessor(&orderService{store: order.NewStore()})
	framing := thrift.NewTFramedTransportFactory(thrift.NewTTransportFactory())
	encoding := thrift.NewTBinaryProtocolFactory(true, true)
	server := thrift.NewTSimpleServer4(processor, socket, framing, encoding)
	defer server.Stop()

	// Publish standard binary encoding over framed TCP.
	publishHost := os.Getenv("METEOR_ORDER_PUBLISH_HOST")
	if publishHost == "" {
		publishHost = os.Getenv("METEOR_TEST_PEER_HOST")
	}
	if publishHost == "" {
		publishHost = bindHost
	}
	_, actualPort, err := net.SplitHostPort(socket.Addr().String())
	if err != nil {
		return err
	}
	address := net.JoinHostPort(publishHost, actualPort)
	if err := order.Ready("thrift://" + address); err != nil {
		return err
	}
	return server.Serve()
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}
