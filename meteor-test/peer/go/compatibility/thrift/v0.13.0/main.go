// Echo binding for Apache Thrift SDK 0.13.0. The official runtime owns all message encoding.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/apache/thrift/lib/go/thrift"
)

const sdkVersion = "0.13.0"

// The contract has argument field 1 and success field 0, as declared in echo.thrift.
type echoValue struct {
	value   string
	fieldID int16
}

func (v *echoValue) Write(p thrift.TProtocol) error {
	if err := p.WriteStructBegin("echo_value"); err != nil {
		return err
	}
	if err := p.WriteFieldBegin("value", thrift.STRING, v.fieldID); err != nil {
		return err
	}
	if err := p.WriteString(v.value); err != nil {
		return err
	}
	if err := p.WriteFieldEnd(); err != nil {
		return err
	}
	if err := p.WriteFieldStop(); err != nil {
		return err
	}
	return p.WriteStructEnd()
}

func (v *echoValue) Read(p thrift.TProtocol) error {
	if _, err := p.ReadStructBegin(); err != nil {
		return err
	}
	found := false
	for {
		_, kind, fieldID, err := p.ReadFieldBegin()
		if err != nil {
			return err
		}
		if kind == thrift.STOP {
			break
		}
		if kind == thrift.STRING && fieldID == v.fieldID {
			v.value, err = p.ReadString()
			found = err == nil
		} else {
			err = p.Skip(kind)
		}
		if err != nil {
			return err
		}
		if err := p.ReadFieldEnd(); err != nil {
			return err
		}
	}
	if err := p.ReadStructEnd(); err != nil {
		return err
	}
	if !found {
		return errors.New("echo response/argument is missing its string field")
	}
	return nil
}

type echoProcessor struct{}

func (p *echoProcessor) Process(ctx context.Context, in, out thrift.TProtocol) (bool, thrift.TException) {
	method, kind, sequence, err := in.ReadMessageBegin()
	if err != nil {
		return false, err
	}
	args := &echoValue{fieldID: 1}
	if err = args.Read(in); err != nil {
		return false, err
	}
	if err = in.ReadMessageEnd(); err != nil {
		return false, err
	}

	// Business failures use the runtime's standard application-exception response.
	var failure thrift.TApplicationException
	if method != "echo" || kind != thrift.CALL {
		failure = thrift.NewTApplicationException(thrift.UNKNOWN_METHOD, "Unknown method: "+method)
	} else if args.value == "fail" {
		failure = thrift.NewTApplicationException(thrift.INTERNAL_ERROR, "Go Thrift failure: fail")
	}
	responseKind := thrift.REPLY
	if failure != nil {
		responseKind = thrift.EXCEPTION
	}
	if err = out.WriteMessageBegin(method, responseKind, sequence); err != nil {
		return false, err
	}
	if failure != nil {
		err = failure.Write(out)
	} else {
		err = (&echoValue{value: args.value + "|go-thrift", fieldID: 0}).Write(out)
	}
	if err != nil {
		return false, err
	}
	if err = out.WriteMessageEnd(); err != nil {
		return false, err
	}
	if err = out.Flush(ctx); err != nil {
		return false, err
	}
	return true, nil
}

func settings(profile string) (thrift.TProtocolFactory, thrift.TTransportFactory, error) {
	parts := strings.Split(profile, "-")
	if len(parts) != 3 || parts[0] != "thrift" {
		return nil, nil, fmt.Errorf("unsupported profile %q", profile)
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

func writeFile(path string, data []byte) error {
	if err := os.WriteFile(path+".part", data, 0600); err != nil {
		return err
	}
	return os.Rename(path+".part", path)
}

func serve(profile, readyFile string) error {
	protocol, transport, err := settings(profile)
	if err != nil {
		return err
	}
	socket, err := thrift.NewTServerSocketTimeout("0.0.0.0:0", 10*time.Second)
	if err != nil {
		return err
	}
	if err := socket.Listen(); err != nil {
		return err
	}
	defer socket.Close()

	// Bind before announcing readiness; this process owns its listener and server lifecycle.
	server := thrift.NewTSimpleServer4(&echoProcessor{}, socket, transport, protocol)
	defer server.Stop()
	finished := make(chan error, 1)
	go func() { finished <- server.Serve() }()
	if err := writeFile(readyFile, []byte("tcp://"+socket.Addr().String())); err != nil {
		return err
	}
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(stop)
	select {
	case <-stop:
		return nil
	case err := <-finished:
		return err
	}
}

func call(profile, target, scenario, resultFile, expectedSuffix string) error {
	protocol, factory, err := settings(profile)
	if err != nil {
		return err
	}
	target = strings.TrimPrefix(target, "tcp://")
	socket, err := thrift.NewTSocketTimeout(target, 10*time.Second)
	if err != nil {
		return err
	}
	transport, err := factory.GetTransport(socket)
	if err != nil {
		return err
	}
	if err := transport.Open(); err != nil {
		return err
	}
	defer transport.Close()

	// A single real call selects Unicode round-trip or a readable application error.
	value := "Go → Meteor 中文🙂"
	if scenario == "error" {
		value = "fail"
	}
	if scenario != "unicode" && scenario != "error" {
		return fmt.Errorf("unsupported scenario %q", scenario)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	client := thrift.NewTStandardClient(protocol.GetProtocol(transport), protocol.GetProtocol(transport))
	args := &echoValue{value: value, fieldID: 1}
	reply := &echoValue{fieldID: 0}
	err = client.Call(ctx, "echo", args, reply)
	result := map[string]interface{}{"ok": true, "sdk": "apache-thrift", "version": sdkVersion}
	if scenario == "error" {
		var exception thrift.TApplicationException
		if !errors.As(err, &exception) || !strings.Contains(exception.Error(), "fail") {
			return fmt.Errorf("expected application failure containing fail, got %v", err)
		}
		result["error"] = exception.Error()
	} else {
		if err != nil {
			return err
		}
		if reply.value != value+expectedSuffix {
			return fmt.Errorf("echo differs: expected %q, received %q", value+expectedSuffix, reply.value)
		}
		result["value"] = reply.value
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		return err
	}
	return writeFile(resultFile, encoded)
}

func main() {
	role := flag.String("role", "", "server or client")
	profile := flag.String("protocol", "thrift-binary-framed", "Thrift encoding and framing")
	target := flag.String("target", "", "host:port")
	scenario := flag.String("scenario", "unicode", "unicode or error")
	readyFile := flag.String("ready-file", "", "server readiness path")
	resultFile := flag.String("result-file", "", "client result path")
	expectedSuffix := flag.String("expected-suffix", "|meteor", "expected echo suffix")
	tlsMode := flag.String("tls-mode", "plain", "compatibility profiles currently use plain transport")
	flag.String("server-name", "", "unused for plain transport")
	flag.String("cert-dir", "", "unused for plain transport")
	flag.Parse()
	if *tlsMode != "plain" {
		fmt.Fprintln(os.Stderr, "historical Thrift profiles require plain transport")
		os.Exit(1)
	}
	var err error
	switch *role {
	case "server":
		err = serve(*profile, *readyFile)
	case "client":
		err = call(*profile, *target, *scenario, *resultFile, *expectedSuffix)
	default:
		err = fmt.Errorf("unsupported role %q", *role)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
