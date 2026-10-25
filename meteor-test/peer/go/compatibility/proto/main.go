// Versioned official SDK peers share the order contract, without Meteor runtime dependencies.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
	"meteor.test/orderpeer/gen/orderpb"
	"meteor.test/orderpeer/gen/orderpb/orderpbconnect"
)

type options struct {
	protocol, role, target, scenario, readyFile, resultFile, tlsMode string
}

type result struct {
	OK      bool   `json:"ok"`
	Code    string `json:"code,omitempty"`
	Message string `json:"message,omitempty"`
}

// Copies prevent either SDK from sharing a mutable request with subsequent calls.
type repository struct {
	mu     sync.RWMutex
	orders map[int32]*orderpb.Order
}

func (r *repository) save(order *orderpb.Order) *orderpb.Order {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.orders[order.Id] = proto.Clone(order).(*orderpb.Order)
	return proto.Clone(order).(*orderpb.Order)
}

func (r *repository) find(id int32) (*orderpb.Order, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	order, found := r.orders[id]
	if !found {
		return nil, fmt.Errorf("Order not found: %d", id)
	}
	return proto.Clone(order).(*orderpb.Order), nil
}

type grpcOrders struct {
	orderpb.UnimplementedOrderDemoServiceServer
	repository *repository
}

func (s *grpcOrders) Save(_ context.Context, order *orderpb.Order) (*orderpb.Order, error) {
	return s.repository.save(order), nil
}

func (s *grpcOrders) Find(_ context.Context, query *orderpb.OrderQuery) (*orderpb.Order, error) {
	order, err := s.repository.find(query.Id)
	if err != nil {
		return nil, status.Error(codes.NotFound, err.Error())
	}
	return order, nil
}

type connectOrders struct{ repository *repository }

func (s *connectOrders) Save(_ context.Context, req *connect.Request[orderpb.Order]) (*connect.Response[orderpb.Order], error) {
	return connect.NewResponse(s.repository.save(req.Msg)), nil
}

func (s *connectOrders) Find(_ context.Context, req *connect.Request[orderpb.OrderQuery]) (*connect.Response[orderpb.Order], error) {
	order, err := s.repository.find(req.Msg.Id)
	if err != nil {
		return nil, connect.NewError(connect.CodeNotFound, err)
	}
	return connect.NewResponse(order), nil
}

func writeFile(path string, data []byte) error {
	if path == "" {
		return errors.New("ready/result file is required for the selected peer role")
	}
	if err := os.WriteFile(path+".part", data, 0600); err != nil {
		return err
	}
	return os.Rename(path+".part", path)
}

func serve(opts options) error {
	// Bind an independent port before publishing the SDK-specific service.
	listener, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	store := &repository{orders: make(map[int32]*orderpb.Order)}
	done := make(chan error, 1)
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(stop)
	address := listener.Addr().String()
	if opts.protocol == "grpc" {
		server := grpc.NewServer()
		defer server.Stop()
		orderpb.RegisterOrderDemoServiceServer(server, &grpcOrders{repository: store})
		go func() { done <- server.Serve(listener) }()
	} else {
		mux := http.NewServeMux()
		path, handler := orderpbconnect.NewOrderDemoServiceHandler(&connectOrders{repository: store})
		mux.Handle(path, handler)
		server := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
		defer server.Close()
		go func() { done <- server.Serve(listener) }()
		address = "http://" + address
	}

	// Readiness represents the listener owned by this process, never a shared fixture.
	if err := writeFile(opts.readyFile, []byte(address)); err != nil {
		return err
	}
	select {
	case <-stop:
		return nil
	case err := <-done:
		return err
	}
}

type unaryClient struct {
	save  func(context.Context, *orderpb.Order) (*orderpb.Order, error)
	find  func(context.Context, *orderpb.OrderQuery) (*orderpb.Order, error)
	close func() error
}

func newClient(opts options) (*unaryClient, error) {
	if opts.protocol == "grpc" {
		connection, err := grpc.NewClient(opts.target, grpc.WithTransportCredentials(insecure.NewCredentials()))
		if err != nil {
			return nil, err
		}
		client := orderpb.NewOrderDemoServiceClient(connection)
		return &unaryClient{
			save: func(ctx context.Context, order *orderpb.Order) (*orderpb.Order, error) {
				return client.Save(ctx, order)
			},
			find: func(ctx context.Context, query *orderpb.OrderQuery) (*orderpb.Order, error) {
				return client.Find(ctx, query)
			},
			close: connection.Close,
		}, nil
	}
	transport := &http.Transport{}
	client := orderpbconnect.NewOrderDemoServiceClient(&http.Client{Transport: transport}, opts.target)
	return &unaryClient{
		save: func(ctx context.Context, order *orderpb.Order) (*orderpb.Order, error) {
			response, err := client.Save(ctx, connect.NewRequest(order))
			if err != nil {
				return nil, err
			}
			return response.Msg, nil
		},
		find: func(ctx context.Context, query *orderpb.OrderQuery) (*orderpb.Order, error) {
			response, err := client.Find(ctx, connect.NewRequest(query))
			if err != nil {
				return nil, err
			}
			return response.Msg, nil
		},
		close: func() error { transport.CloseIdleConnections(); return nil },
	}, nil
}

func equalOrder(expected, actual *orderpb.Order) error {
	if actual == nil || expected.Id != actual.Id || expected.Description != actual.Description ||
		!bytes.Equal(expected.Attachment, actual.Attachment) {
		return fmt.Errorf("order differs: expected %v, actual %v", expected, actual)
	}
	return nil
}

func call(opts options) (result, error) {
	client, err := newClient(opts)
	if err != nil {
		return result{}, err
	}
	defer client.close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if opts.scenario == "missing" {
		// Verify Meteor's error mapping independently of the successful order scenario.
		_, failure := client.find(ctx, &orderpb.OrderQuery{Id: 404})
		if failure == nil || !strings.Contains(failure.Error(), "Order not found: 404") {
			return result{}, fmt.Errorf("missing order returned a different failure: %v", failure)
		}
		if opts.protocol == "grpc" {
			if status.Code(failure) != codes.Internal {
				return result{}, fmt.Errorf("expected Meteor gRPC Internal, received: %w", failure)
			}
			return result{OK: true, Code: status.Code(failure).String(), Message: failure.Error()}, nil
		}
		if connect.CodeOf(failure) != connect.CodeInvalidArgument {
			return result{}, fmt.Errorf("expected Meteor Connect invalid_argument, received: %w", failure)
		}
		return result{OK: true, Code: connect.CodeOf(failure).String(), Message: failure.Error()}, nil
	}
	if opts.scenario != "unicode" {
		return result{}, fmt.Errorf("unsupported order scenario %q", opts.scenario)
	}

	// The same contract vector is used in every SDK version and in both directions.
	order := &orderpb.Order{
		Id: 101, Description: "订单-你好-🌍-café", Attachment: []byte{0, 1, 127, 128, 255},
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

func main() {
	var opts options
	flag.StringVar(&opts.protocol, "protocol", "", "grpc or connect")
	flag.StringVar(&opts.role, "role", "", "server or client")
	flag.StringVar(&opts.target, "target", "", "gRPC host:port or Connect base URL")
	flag.StringVar(&opts.scenario, "scenario", "", "unicode or missing")
	flag.StringVar(&opts.readyFile, "ready-file", "", "write bound address atomically when ready")
	flag.StringVar(&opts.resultFile, "result-file", "", "write successful client JSON result atomically")
	flag.StringVar(&opts.tlsMode, "tls-mode", "plain", "version compatibility peers use plain transport")
	flag.Parse()
	var err error
	if opts.protocol != "grpc" && opts.protocol != "connect" {
		err = fmt.Errorf("unsupported protocol %q", opts.protocol)
	} else if opts.tlsMode != "plain" {
		err = fmt.Errorf("unsupported TLS mode %q", opts.tlsMode)
	} else {
		switch opts.role {
		case "server":
			err = serve(opts)
		case "client":
			var outcome result
			outcome, err = call(opts)
			if err == nil {
				var encoded []byte
				encoded, err = json.Marshal(outcome)
				if err == nil {
					err = writeFile(opts.resultFile, encoded)
				}
			}
		default:
			err = fmt.Errorf("unsupported role %q", opts.role)
		}
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
