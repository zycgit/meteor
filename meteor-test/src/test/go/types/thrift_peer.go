// Apache Thrift carries float32 as double and uint64 as an explicit decimal string.
package main

import (
	"context"
	"fmt"
	"math"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/apache/thrift/lib/go/thrift"
	"meteor.test/orderpeer/gen/typecontract"
)

type thriftTypes struct{}

func (s *thriftTypes) Echo(_ context.Context, request *typecontract.TypeValue) (*typecontract.TypeValue, error) {
	// Restore the declared Go types before invoking the same business contract.
	value, err := nativeFromThrift(request)
	if err != nil {
		return nil, err
	}
	response, err := acceptVector(value)
	if err != nil {
		return nil, err
	}
	return thriftFromNative(response), nil
}

func thriftTypeProtocol(name string) (thrift.TProtocolFactory, error) {
	// Framing is fixed for this type matrix; each encoding has its own case identity.
	if strings.Contains(name, "compact") {
		return thrift.NewTCompactProtocolFactory(), nil
	}
	if strings.Contains(name, "json") {
		return thrift.NewTJSONProtocolFactory(), nil
	}
	if name == "thrift" || strings.Contains(name, "binary") {
		return thrift.NewTBinaryProtocolFactory(true, true), nil
	}
	return nil, fmt.Errorf("unsupported Thrift type profile %q", name)
}

func serveThrift(opts options) error {
	// Bind first, and publish the actual ephemeral address only after SDK startup.
	protocol, err := thriftTypeProtocol(opts.protocol)
	if err != nil {
		return err
	}
	listener, err := thrift.NewTServerSocketTimeout("0.0.0.0:0", 10*time.Second)
	if err != nil {
		return err
	}
	if err := listener.Listen(); err != nil {
		return err
	}
	defer listener.Close()
	transport := thrift.NewTFramedTransportFactory(thrift.NewTTransportFactory())
	processor := typecontract.NewTypeEchoServiceProcessor(&thriftTypes{})
	server := thrift.NewTSimpleServer4(processor, listener, transport, protocol)
	thrift.ServerStopTimeout = time.Second
	defer server.Stop()
	done := make(chan error, 1)
	go func() { done <- server.Serve() }()
	if err := writeReady(opts.readyFile, listener.Addr().String()); err != nil {
		return err
	}

	// This peer owns only its own listener; the outer test owns the container.
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

func callThrift(opts options) error {
	// Fixture decimal text is converted to real int64/uint64 before serialization.
	expected, err := loadVector(opts.vector)
	if err != nil {
		return err
	}
	protocol, err := thriftTypeProtocol(opts.protocol)
	if err != nil {
		return err
	}
	config := &thrift.TConfiguration{ConnectTimeout: 5 * time.Second, SocketTimeout: 10 * time.Second}
	socket := thrift.NewTSocketConf(opts.target, config)
	factory := thrift.NewTFramedTransportFactory(thrift.NewTTransportFactory())
	transport, err := factory.GetTransport(socket)
	if err != nil {
		return err
	}
	if err := transport.Open(); err != nil {
		transport.Close()
		return err
	}
	defer transport.Close()
	client := typecontract.NewTypeEchoServiceClientFactory(transport, protocol)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	response, err := client.Echo(ctx, thriftFromNative(expected))
	if err != nil {
		return err
	}

	// Validate the selected typed field and the provider-created business receipt.
	actual, err := nativeFromThrift(response)
	if err != nil {
		return err
	}
	return validateResponse(actual)
}

func thriftFromNative(value *nativeValue) *typecontract.TypeValue {
	result := &typecontract.TypeValue{
		CaseId: value.CaseId, BooleanValue: value.BooleanValue,
		Int8Value: value.Int8Value, Int16Value: value.Int16Value,
		Int32Value: value.Int32Value, Int64Value: value.Int64Value,
		Float32Value: float64(value.Float32Value), Float64Value: value.Float64Value,
		TextValue: value.TextValue, BinaryValue: append([]byte{}, value.BinaryValue...),
		EnumValue: typecontract.TypeKind(value.EnumValue), ListValue: append([]string{}, value.ListValue...),
		MapValue: value.MapValue, OptionalValue: value.OptionalValue, Receipt: value.Receipt,
		Unsigned64Value: strconv.FormatUint(value.Unsigned64Value, 10),
	}
	if value.RecordValue != nil {
		result.RecordValue = &typecontract.TypeRecord{Index: value.RecordValue.Index, Name: value.RecordValue.Name}
	}
	return result
}

func nativeFromThrift(value *typecontract.TypeValue) (*nativeValue, error) {
	if value == nil {
		return nil, fmt.Errorf("Thrift returned a nil type value")
	}
	// The wider Thrift scalar must preserve the exact original float32 value.
	single := float32(value.Float32Value)
	if math.Float64bits(float64(single)) != math.Float64bits(value.Float32Value) {
		return nil, fmt.Errorf("Thrift double cannot narrow exactly to float32: %v", value.Float32Value)
	}
	unsigned, err := strconv.ParseUint(value.Unsigned64Value, 10, 64)
	if err != nil {
		return nil, fmt.Errorf("Thrift unsigned decimal mapping: %w", err)
	}
	result := &nativeValue{
		CaseId: value.CaseId, BooleanValue: value.BooleanValue,
		Int8Value: value.Int8Value, Int16Value: value.Int16Value,
		Int32Value: value.Int32Value, Int64Value: value.Int64Value,
		Float32Value: single, Float64Value: value.Float64Value,
		TextValue: value.TextValue, BinaryValue: append([]byte{}, value.BinaryValue...),
		EnumValue: int32(value.EnumValue), ListValue: append([]string{}, value.ListValue...),
		MapValue: value.MapValue, OptionalValue: value.OptionalValue, Receipt: value.Receipt,
		Unsigned64Value: unsigned,
	}
	if value.RecordValue != nil {
		result.RecordValue = &typeRecord{Index: value.RecordValue.Index, Name: value.RecordValue.Name}
	}
	return result, nil
}
