// Official Apache Thrift Go peer. The repository and vectors are shared with the other Go adapters.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/apache/thrift/lib/go/thrift"
	"meteor.test/orderpeer/gen/orderpb"
	"meteor.test/orderpeer/gen/thriftorder"
)

type thriftOrders struct{ repository *repository }

func (s *thriftOrders) Save(_ context.Context, order *thriftorder.ThriftOrder) (*thriftorder.ThriftOrder, error) {
	return thriftOrder(s.repository.save(protoOrder(order))), nil
}

func (s *thriftOrders) Find(_ context.Context, query *thriftorder.OrderQuery) (*thriftorder.ThriftOrder, error) {
	order, err := s.repository.find(query.ID)
	if err != nil {
		return nil, thrift.NewTApplicationException(thrift.INTERNAL_ERROR, err.Error())
	}
	return thriftOrder(order), nil
}

func protoOrder(order *thriftorder.ThriftOrder) *orderpb.Order {
	return &orderpb.Order{Id: order.ID, Description: order.GetDescription(), Attachment: order.Attachment}
}

func thriftOrder(order *orderpb.Order) *thriftorder.ThriftOrder {
	// The common protobuf-backed repository may normalize an empty byte slice to nil.
	// The Thrift contract explicitly returns the fields supplied by this demo, including empty values.
	description := order.Description
	return &thriftorder.ThriftOrder{
		ID: order.Id, Description: &description, Attachment: append([]byte{}, order.Attachment...),
	}
}

func thriftSettings(opts options) (thrift.TProtocolFactory, thrift.TTransportFactory, error) {
	parts := strings.Split(opts.protocol, "-")
	if len(parts) != 3 || parts[0] != "thrift" {
		return nil, nil, fmt.Errorf("unsupported Thrift profile %q", opts.protocol)
	}
	var protocol thrift.TProtocolFactory
	switch parts[1] {
	case "binary":
		protocol = thrift.NewTBinaryProtocolFactory(true, true)
	case "compact":
		protocol = thrift.NewTCompactProtocolFactory()
	case "json":
		protocol = thrift.NewTJSONProtocolFactory()
	default:
		return nil, nil, fmt.Errorf("unsupported Thrift encoding %q", parts[1])
	}

	// Framing wraps the SDK transport. It does not replace the selected Thrift protocol.
	transport := thrift.NewTTransportFactory()
	switch parts[2] {
	case "framed":
		transport = thrift.NewTFramedTransportFactory(transport)
	case "unframed":
	default:
		return nil, nil, fmt.Errorf("unsupported Thrift framing %q", parts[2])
	}
	return protocol, transport, nil
}

func serveThrift(opts options) error {
	protocol, transport, err := thriftSettings(opts)
	if err != nil {
		return err
	}
	config, err := tlsConfiguration(opts, true)
	if err != nil {
		return err
	}

	// Both sockets bind their real ephemeral port before the ready file is published.
	var listener thrift.TServerTransport
	var address func() string
	if config == nil {
		socket, err := thrift.NewTServerSocketTimeout("0.0.0.0:0", 10*time.Second)
		if err != nil {
			return err
		}
		listener = socket
		address = func() string { return socket.Addr().String() }
	} else {
		socket, err := thrift.NewTSSLServerSocketTimeout("0.0.0.0:0", config, 10*time.Second)
		if err != nil {
			return err
		}
		listener = socket
		address = func() string { return socket.Addr().String() }
	}
	if err := listener.Listen(); err != nil {
		return err
	}
	defer listener.Close()

	// Generated Processor delegates to the same order repository used by gRPC and Connect.
	store := &repository{orders: make(map[int32]*orderpb.Order)}
	processor := thriftorder.NewOrderThriftServiceProcessor(&thriftOrders{repository: store})
	server := thrift.NewTSimpleServer4(processor, listener, transport, protocol)
	thrift.ServerStopTimeout = time.Second
	defer server.Stop()
	done := make(chan error, 1)
	go func() { done <- server.Serve() }()
	if err := writeFile(opts.readyFile, []byte(address())); err != nil {
		return err
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(stop)
	select {
	case <-stop:
		return nil
	case err := <-done:
		return err
	}
}

func newThriftClient(opts options) (*unaryClient, error) {
	protocol, factory, err := thriftSettings(opts)
	if err != nil {
		return nil, err
	}
	tlsConfig, err := tlsConfiguration(opts, false)
	if err != nil {
		return nil, err
	}
	config := &thrift.TConfiguration{
		ConnectTimeout: 5 * time.Second,
		SocketTimeout:  10 * time.Second,
		TLSConfig:      tlsConfig,
	}

	// The official SDK owns both the plain/TLS socket and optional framing wrapper.
	var socket thrift.TTransport = thrift.NewTSocketConf(opts.target, config)
	if tlsConfig != nil {
		socket = thrift.NewTSSLSocketConf(opts.target, config)
	}
	transport, err := factory.GetTransport(socket)
	if err != nil {
		return nil, err
	}
	if err := transport.Open(); err != nil {
		transport.Close()
		return nil, err
	}
	client := thriftorder.NewOrderThriftServiceClientFactory(transport, protocol)
	return &unaryClient{
		save: func(ctx context.Context, order *orderpb.Order) (*orderpb.Order, error) {
			saved, err := client.Save(ctx, thriftOrder(order))
			if err != nil {
				return nil, err
			}
			return protoOrder(saved), nil
		},
		find: func(ctx context.Context, query *orderpb.OrderQuery) (*orderpb.Order, error) {
			found, err := client.Find(ctx, &thriftorder.OrderQuery{ID: query.Id})
			if err != nil {
				return nil, err
			}
			return protoOrder(found), nil
		},
		close: transport.Close,
	}, nil
}

func callThrift(opts options) (result, error) {
	client, err := newThriftClient(opts)
	if err != nil {
		return result{}, err
	}
	defer client.close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// A missing business order must be a standard application error, not EOF or a timeout.
	if opts.scenario == "missing" {
		_, failure := client.find(ctx, &orderpb.OrderQuery{Id: 404})
		var application thrift.TApplicationException
		if !errors.As(failure, &application) || application.TypeId() != thrift.INTERNAL_ERROR {
			return result{}, fmt.Errorf("expected Thrift INTERNAL_ERROR, received %v", failure)
		}
		if !strings.Contains(application.Error(), "Order not found: 404") {
			return result{}, fmt.Errorf("unexpected missing-order error: %w", failure)
		}
		return result{OK: true, Code: "INTERNAL_ERROR", Message: application.Error()}, nil
	}

	// Reuse the exact input and equality contract of the other Go protocol peers.
	order, err := orderVector(opts.scenario)
	if err != nil {
		return result{}, err
	}
	saved, err := client.save(ctx, order)
	if err != nil {
		return result{}, fmt.Errorf("save: %w", err)
	}
	if err := equalOrder(order, saved); err != nil {
		return result{}, fmt.Errorf("save response: %w", err)
	}
	found, err := client.find(ctx, &orderpb.OrderQuery{Id: order.Id})
	if err != nil {
		return result{}, fmt.Errorf("find: %w", err)
	}
	if err := equalOrder(order, found); err != nil {
		return result{}, fmt.Errorf("find response: %w", err)
	}
	return result{OK: true}, nil
}

func runThrift(opts options) error {
	if opts.role == "server" {
		return serveThrift(opts)
	}
	if opts.role != "client" {
		return fmt.Errorf("unsupported role %q", opts.role)
	}
	outcome, err := callThrift(opts)
	if err != nil {
		return err
	}
	encoded, err := json.Marshal(outcome)
	if err != nil {
		return err
	}
	return writeFile(opts.resultFile, encoded)
}
