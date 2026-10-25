package main

import (
	"context"
	"errors"
	"fmt"
	"math"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"meteor.test/orderpeer/gen/typepb"
	"meteor.test/orderpeer/gen/typepb/typepbconnect"
)

func protoValue(value *nativeValue) *typepb.TypeValue {
	result := &typepb.TypeValue{
		CaseId: value.CaseId, BooleanValue: value.BooleanValue,
		Int8Value: int32(value.Int8Value), Int16Value: int32(value.Int16Value),
		Int32Value: value.Int32Value, Int64Value: value.Int64Value,
		Float32Value: value.Float32Value, Float64Value: value.Float64Value,
		TextValue: value.TextValue, BinaryValue: value.BinaryValue,
		EnumValue: typepb.TypeKind(value.EnumValue), ListValue: value.ListValue,
		MapValue: value.MapValue, OptionalValue: value.OptionalValue,
		Receipt: value.Receipt, Unsigned64Value: value.Unsigned64Value,
	}
	if value.RecordValue != nil {
		result.RecordValue = &typepb.TypeRecord{Index: value.RecordValue.Index, Name: value.RecordValue.Name}
	}
	return result
}

func nativeProto(value *typepb.TypeValue) (*nativeValue, error) {
	if value == nil {
		return nil, errors.New("missing protobuf response")
	}
	// Protobuf declares int32 for narrow signed fields: validate before conversion.
	if value.Int8Value < math.MinInt8 || value.Int8Value > math.MaxInt8 {
		return nil, fmt.Errorf("int8 overflow: %d", value.Int8Value)
	}
	if value.Int16Value < math.MinInt16 || value.Int16Value > math.MaxInt16 {
		return nil, fmt.Errorf("int16 overflow: %d", value.Int16Value)
	}
	result := &nativeValue{
		CaseId: value.CaseId, BooleanValue: value.BooleanValue,
		Int8Value: int8(value.Int8Value), Int16Value: int16(value.Int16Value),
		Int32Value: value.Int32Value, Int64Value: value.Int64Value,
		Float32Value: value.Float32Value, Float64Value: value.Float64Value,
		TextValue: value.TextValue, BinaryValue: value.BinaryValue,
		EnumValue: int32(value.EnumValue), ListValue: value.ListValue,
		MapValue: value.MapValue, OptionalValue: value.OptionalValue,
		Receipt: value.Receipt, Unsigned64Value: value.Unsigned64Value,
	}
	if value.RecordValue != nil {
		result.RecordValue = &typeRecord{Index: value.RecordValue.Index, Name: value.RecordValue.Name}
	}
	return result, nil
}

type grpcTypes struct {
	typepb.UnimplementedTypeEchoServiceServer
}

func (*grpcTypes) Echo(_ context.Context, request *typepb.TypeValue) (*typepb.TypeValue, error) {
	value, err := nativeProto(request)
	if err != nil {
		return nil, err
	}
	response, err := acceptVector(value)
	if err != nil {
		return nil, err
	}
	return protoValue(response), nil
}

type connectTypes struct{}

func (*connectTypes) Echo(ctx context.Context, request *connect.Request[typepb.TypeValue]) (*connect.Response[typepb.TypeValue], error) {
	response, err := (&grpcTypes{}).Echo(ctx, request.Msg)
	if err != nil {
		return nil, connect.NewError(connect.CodeInvalidArgument, err)
	}
	return connect.NewResponse(response), nil
}

func serveProto(opts options) error {
	// The SDK chooses a real listening port; the creator owns listener/server shutdown.
	listener, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	done := make(chan error, 1)
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, os.Interrupt)
	defer signal.Stop(stop)
	address := listener.Addr().String()
	if opts.protocol == "grpc" {
		server := grpc.NewServer()
		defer server.Stop()
		typepb.RegisterTypeEchoServiceServer(server, &grpcTypes{})
		go func() { done <- server.Serve(listener) }()
	} else {
		path, handler := typepbconnect.NewTypeEchoServiceHandler(&connectTypes{})
		mux := http.NewServeMux()
		mux.Handle(path, handler)
		server := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
		defer server.Close()
		go func() { done <- server.Serve(listener) }()
		address = "http://" + address
	}
	if err := writeReady(opts.readyFile, address); err != nil {
		return err
	}
	select {
	case <-stop:
		return nil
	case err := <-done:
		return err
	}
}

func callProto(opts options) error {
	// One vector is exactly one typed request/response exchange through the official SDK.
	request, err := loadVector(opts.vector)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	var response *typepb.TypeValue
	if opts.protocol == "grpc" {
		target := strings.TrimPrefix(opts.target, "http://")
		connection, err := grpc.NewClient(target, grpc.WithTransportCredentials(insecure.NewCredentials()))
		if err != nil {
			return err
		}
		defer connection.Close()
		response, err = typepb.NewTypeEchoServiceClient(connection).Echo(ctx, protoValue(request))
		if err != nil {
			return err
		}
	} else {
		transport := &http.Transport{Proxy: nil}
		defer transport.CloseIdleConnections()
		client := &http.Client{Transport: transport, Timeout: 10 * time.Second}
		stub := typepbconnect.NewTypeEchoServiceClient(client, opts.target)
		reply, err := stub.Echo(ctx, connect.NewRequest(protoValue(request)))
		if err != nil {
			return err
		}
		response = reply.Msg
	}
	actual, err := nativeProto(response)
	if err != nil {
		return err
	}
	return validateResponse(actual)
}
