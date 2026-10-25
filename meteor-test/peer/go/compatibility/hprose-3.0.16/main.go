// Standalone Hprose SDK 3.0.16 peer. The SDK owns RPC encoding and error envelopes.
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/hprose/hprose-golang/v3/rpc/core"
	hprosehttp "github.com/hprose/hprose-golang/v3/rpc/http"
)

const sdkVersion = "3.0.16"
const sample = "Go → Meteor 中文🙂"

func echo(value string) (string, error) {
	if value == "fail" {
		return "", errors.New("version-peer-error")
	}
	return value + "|go-hprose", nil
}

func writeFile(path string, data []byte) error {
	if err := os.WriteFile(path+".part", data, 0600); err != nil {
		return err
	}
	return os.Rename(path+".part", path)
}

func serve(readyFile string) error {
	// Prepare the SDK service before opening the listener.
	hprosehttp.RegisterHandler()
	service := core.NewService()
	service.AddFunction(echo, "sample_Echo")
	listener, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	server := &http.Server{ReadHeaderTimeout: 10 * time.Second}
	if err := service.Bind(server); err != nil {
		return err
	}
	defer server.Close()

	// Publish the bound address; the process owner stops only this server.
	done := make(chan error, 1)
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(stop)
	go func() { done <- server.Serve(listener) }()
	if err := writeFile(readyFile, []byte("http://"+listener.Addr().String())); err != nil {
		return err
	}
	select {
	case <-stop:
		return nil
	case err := <-done:
		return err
	}
}

func call(target, scenario, resultFile, expectedSuffix string) error {
	// A proxy call uses the independent SDK's transport and serializer.
	hprosehttp.RegisterTransport()
	client := core.NewClient(target)
	defer client.Abort()
	client.Timeout = 10 * time.Second
	var proxy struct {
		Echo func(string) (string, error) `name:"sample_Echo"`
	}
	client.UseService(&proxy)
	value := sample
	if scenario == "error" {
		value = "fail"
	} else if scenario != "unicode" {
		return fmt.Errorf("unsupported scenario %q", scenario)
	}
	actual, err := proxy.Echo(value)

	// Each execution checks either one Unicode reply or one application failure.
	result := map[string]interface{}{"ok": true, "sdk": "hprose-golang", "version": sdkVersion}
	if scenario == "error" {
		if err == nil || !strings.Contains(err.Error(), "version-peer-error") {
			return fmt.Errorf("expected version-peer-error, got %v", err)
		}
		result["error"] = err.Error()
	} else {
		if err != nil {
			return err
		}
		if actual != sample+expectedSuffix {
			return fmt.Errorf("echo differs: expected %q, received %q", sample+expectedSuffix, actual)
		}
		result["value"] = actual
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		return err
	}
	return writeFile(resultFile, encoded)
}

func main() {
	role := flag.String("role", "", "server or client")
	protocol := flag.String("protocol", "hprose", "hprose")
	target := flag.String("target", "", "HTTP service endpoint")
	scenario := flag.String("scenario", "unicode", "unicode or error")
	readyFile := flag.String("ready-file", "", "server readiness path")
	resultFile := flag.String("result-file", "", "client result path")
	expectedSuffix := flag.String("expected-suffix", "|meteor", "expected response suffix")
	flag.Parse()

	var err error
	if *protocol != "hprose" {
		err = fmt.Errorf("unsupported protocol %q", *protocol)
	} else {
		switch *role {
		case "server":
			err = serve(*readyFile)
		case "client":
			err = call(*target, *scenario, *resultFile, *expectedSuffix)
		default:
			err = fmt.Errorf("unsupported role %q", *role)
		}
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
