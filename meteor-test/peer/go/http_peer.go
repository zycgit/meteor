// Independent SDKs establish basic Hprose, JSON-RPC 2.0 and XML-RPC HTTP interoperability.
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	gorillarpc "github.com/gorilla/rpc/v2"
	"github.com/gorilla/rpc/v2/json2"
	hproserpc "github.com/hprose/hprose-golang/rpc"
	"github.com/kolo/xmlrpc"
)

const textSample = "Go → Meteor 中文🙂"

// Gorilla exposes exported Go method names; Java maps echo to Echo using MetMethod.
type textEchoService struct{}

func (*textEchoService) Echo(_ *http.Request, value *string, reply *string) error {
	*reply = *value + "|go-jsonrpc"
	return nil
}

func serveTextPeer(opts options) error {
	// Use each SDK's own HTTP handler and a per-scenario listener.
	var handler http.Handler
	switch opts.protocol {
	case "hprose":
		service := hproserpc.NewHTTPService()
		service.AddFunction("sample_Echo", func(value string) string { return value + "|go-hprose" })
		handler = service
	case "jsonrpc":
		service := gorillarpc.NewServer()
		service.RegisterCodec(json2.NewCodec(), "application/json")
		if err := service.RegisterService(&textEchoService{}, "sample"); err != nil {
			return err
		}
		handler = service
	default:
		return fmt.Errorf("the selected %s SDK has no server API", opts.protocol)
	}
	listener, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	server := &http.Server{Handler: handler, ReadHeaderTimeout: 10 * time.Second}
	defer server.Close()

	// Announce the actual listen address and release only this peer when its owner stops it.
	done := make(chan error, 1)
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(stop)
	go func() { done <- server.Serve(listener) }()
	if err := writeFile(opts.readyFile, []byte("http://"+listener.Addr().String())); err != nil {
		return err
	}
	select {
	case <-stop:
		return nil
	case err := <-done:
		return err
	}
}

func callTextPeer(opts options) (string, error) {
	// Each request is encoded and decoded by the external SDK, not by a handwritten protocol codec.
	switch opts.protocol {
	case "hprose":
		client := hproserpc.NewHTTPClient(opts.target)
		defer client.Close()
		client.SetTimeout(10 * time.Second)
		client.SetRetry(0)
		var service struct {
			Echo func(string) (string, error) `name:"sample_Echo"`
		}
		client.UseService(&service)
		return service.Echo(textSample)
	case "jsonrpc":
		body, err := json2.EncodeClientRequest("sample.Echo", []string{textSample})
		if err != nil {
			return "", err
		}
		transport := &http.Transport{}
		defer transport.CloseIdleConnections()
		client := &http.Client{Transport: transport, Timeout: 10 * time.Second}
		response, err := client.Post(opts.target, "application/json", bytes.NewReader(body))
		if err != nil {
			return "", err
		}
		defer response.Body.Close()
		var reply string
		err = json2.DecodeClientResponse(response.Body, &reply)
		return reply, err
	case "soap":
		return callSoapPeer(opts)
	case "xmlrpc":
		transport := &http.Transport{ResponseHeaderTimeout: 10 * time.Second}
		defer transport.CloseIdleConnections()
		client, err := xmlrpc.NewClient(opts.target, transport)
		if err != nil {
			return "", err
		}
		defer client.Close()
		var reply string
		err = client.Call("sample.Echo", []interface{}{textSample}, &reply)
		return reply, err
	default:
		return "", fmt.Errorf("unsupported text protocol %q", opts.protocol)
	}
}

func runTextPeer(opts options) error {
	if opts.role == "server" {
		return serveTextPeer(opts)
	}
	if opts.role != "client" {
		return fmt.Errorf("unsupported text peer role %q", opts.role)
	}

	// Require a response produced by the remote Meteor provider before publishing success.
	actual, err := callTextPeer(opts)
	if err != nil {
		return err
	}
	expected := textSample + "|meteor"
	if actual != expected {
		return fmt.Errorf("text differs: expected %q, actual %q", expected, actual)
	}
	encoded, err := json.Marshal(result{OK: true, Message: actual})
	if err != nil {
		return err
	}
	return writeFile(opts.resultFile, encoded)
}
