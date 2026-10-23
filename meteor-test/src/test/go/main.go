// Official Go SDK peer. This executable has no Meteor libraries or Java dependencies.
package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
	"meteor.test/orderpeer/gen/orderpb"
	"meteor.test/orderpeer/gen/orderpb/orderpbconnect"
)

type options struct {
	protocol, role, target, scenario, readyFile, resultFile, tlsMode, certDir string
}

type result struct {
	OK      bool   `json:"ok"`
	Code    string `json:"code,omitempty"`
	Message string `json:"message,omitempty"`
}

// The repository is shared by the protocol adapters, and copies both requests and responses.
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

func tlsConfiguration(opts options, server bool) (*tls.Config, error) {
	if opts.tlsMode == "plain" {
		return nil, nil
	}
	if opts.tlsMode != "tls" && opts.tlsMode != "mtls" {
		return nil, fmt.Errorf("unsupported TLS mode %q", opts.tlsMode)
	}
	ca, err := os.ReadFile(filepath.Join(opts.certDir, "ca.pem"))
	if err != nil {
		return nil, err
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(ca) {
		return nil, errors.New("acceptance CA contains no certificate")
	}
	config := &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: pool}
	if server {
		pair, err := tls.LoadX509KeyPair(filepath.Join(opts.certDir, "server.pem"), filepath.Join(opts.certDir, "server-key.pem"))
		if err != nil {
			return nil, err
		}
		config.Certificates = []tls.Certificate{pair}
		if opts.tlsMode == "mtls" {
			config.ClientAuth = tls.RequireAndVerifyClientCert
			config.ClientCAs = pool
		}
	} else if opts.tlsMode == "mtls" {
		pair, err := tls.LoadX509KeyPair(filepath.Join(opts.certDir, "client.pem"), filepath.Join(opts.certDir, "client-key.pem"))
		if err != nil {
			return nil, err
		}
		config.Certificates = []tls.Certificate{pair}
	}
	return config, nil
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
	config, err := tlsConfiguration(opts, true)
	if err != nil {
		return err
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	store := &repository{orders: make(map[int32]*orderpb.Order)}
	done := make(chan error, 1)
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(stop)
	if opts.protocol == "grpc" {
		var settings []grpc.ServerOption
		if config != nil {
			settings = append(settings, grpc.Creds(credentials.NewTLS(config)))
		}
		server := grpc.NewServer(settings...)
		defer server.Stop()
		orderpb.RegisterOrderDemoServiceServer(server, &grpcOrders{repository: store})
		go func() { done <- server.Serve(listener) }()
		if err := writeFile(opts.readyFile, []byte(listener.Addr().String())); err != nil {
			return err
		}
		select {
		case <-stop:
			return nil
		case err := <-done:
			return err
		}
	}
	if opts.protocol != "connect" {
		return fmt.Errorf("unsupported protocol %q", opts.protocol)
	}

	// Standard net/http serves Connect unary over HTTP/1.1; TLS is verified in both directions.
	mux := http.NewServeMux()
	path, handler := orderpbconnect.NewOrderDemoServiceHandler(&connectOrders{repository: store})
	mux.Handle(path, handler)
	server := &http.Server{Handler: mux, TLSConfig: config, ReadHeaderTimeout: 10 * time.Second}
	defer server.Close()
	scheme := "http://"
	if config == nil {
		go func() { done <- server.Serve(listener) }()
	} else {
		scheme = "https://"
		go func() { done <- server.ServeTLS(listener, "", "") }()
	}
	if err := writeFile(opts.readyFile, []byte(scheme+listener.Addr().String())); err != nil {
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
	config, err := tlsConfiguration(opts, false)
	if err != nil {
		return nil, err
	}
	if opts.protocol == "grpc" {
		var transport credentials.TransportCredentials = insecure.NewCredentials()
		if config != nil {
			transport = credentials.NewTLS(config)
		}
		connection, err := grpc.NewClient(opts.target, grpc.WithTransportCredentials(transport))
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
	if opts.protocol != "connect" {
		return nil, fmt.Errorf("unsupported protocol %q", opts.protocol)
	}
	transport := &http.Transport{TLSClientConfig: config}
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

func orderVector(scenario string) (*orderpb.Order, error) {
	attachment := []byte{0, 1, 127, 128, 255}
	switch scenario {
	case "unicode":
		return &orderpb.Order{Id: 101, Description: "订单-你好-🌍-café", Attachment: attachment}, nil
	case "boundary":
		return &orderpb.Order{Id: 2147483647, Description: "boundary", Attachment: attachment}, nil
	case "negative-boundary":
		return &orderpb.Order{Id: -2147483648, Description: "negative-boundary", Attachment: attachment}, nil
	case "empty":
		return &orderpb.Order{Id: 0, Description: "", Attachment: []byte{}}, nil
	default:
		return nil, fmt.Errorf("unsupported order scenario %q", scenario)
	}
}

func equalOrder(expected, actual *orderpb.Order) error {
	if actual == nil || expected.Id != actual.Id || expected.Description != actual.Description || !bytes.Equal(expected.Attachment, actual.Attachment) {
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
		_, failure := client.find(ctx, &orderpb.OrderQuery{Id: 404})
		if failure == nil {
			return result{}, errors.New("missing order unexpectedly succeeded")
		}
		if !strings.Contains(failure.Error(), "Order not found: 404") {
			return result{}, fmt.Errorf("missing order returned a different failure: %w", failure)
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

func main() {
	var opts options
	flag.StringVar(&opts.protocol, "protocol", "", "grpc, connect or thrift-<binary|compact|json>-<framed|unframed>")
	flag.StringVar(&opts.role, "role", "", "server or client")
	flag.StringVar(&opts.target, "target", "", "gRPC host:port or Connect base URL")
	flag.StringVar(&opts.scenario, "scenario", "", "unicode, boundary, negative-boundary, empty, missing")
	flag.StringVar(&opts.readyFile, "ready-file", "", "write bound address atomically when ready")
	flag.StringVar(&opts.resultFile, "result-file", "", "write successful client JSON result atomically")
	flag.StringVar(&opts.tlsMode, "tls-mode", "plain", "plain, tls or mtls")
	flag.StringVar(&opts.certDir, "cert-dir", "", "directory containing acceptance PEM files")
	flag.Parse()
	// Thrift has its own SDK Processor and application errors; existing HTTP peers keep their execution path.
	if strings.HasPrefix(opts.protocol, "thrift-") {
		if err := runThrift(opts); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	var err error
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
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
