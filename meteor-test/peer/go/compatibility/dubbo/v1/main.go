// The 1.x SDK is a separate Go module so it cannot resolve through the 3.x runtime.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	_ "github.com/apache/dubbo-go/cluster/cluster_impl"
	_ "github.com/apache/dubbo-go/cluster/loadbalance"
	"github.com/apache/dubbo-go/common/extension"
	_ "github.com/apache/dubbo-go/common/proxy/proxy_factory"
	"github.com/apache/dubbo-go/config"
	_ "github.com/apache/dubbo-go/filter/filter_impl"
	_ "github.com/apache/dubbo-go/protocol/dubbo"
	_ "github.com/apache/dubbo-go/registry/protocol"
)

const requestText = "Go → Meteor 中文🙂"

type options struct {
	role, target, readyFile, resultFile, scenario, protocol string
}

type EchoProvider struct{}

func (*EchoProvider) Reference() string               { return "EchoService" }
func (*EchoProvider) MethodMapper() map[string]string { return map[string]string{"Echo": "echo"} }
func (*EchoProvider) Echo(_ context.Context, value string) (string, error) {
	if value == "fail" {
		return "", errors.New("compat-error")
	}
	return value + "|go-dubbo", nil
}

type EchoConsumer struct {
	Echo func(context.Context, string) (string, error) `dubbo:"echo"`
}

func (*EchoConsumer) Reference() string { return "EchoService" }

func writeFile(path string, content []byte) error {
	if err := os.WriteFile(path+".part", content, 0600); err != nil {
		return err
	}
	return os.Rename(path+".part", path)
}

func configFile(outputPath, body string) (string, error) {
	// Each harness invocation owns its result directory and the SDK configuration beside it.
	path := filepath.Join(filepath.Dir(outputPath), "dubbo-go-1.5.9.yml")
	return path, os.WriteFile(path, []byte(body), 0600)
}

func serve(opts options) error {
	// Reserve the peer's port before handing listener creation to the official SDK.
	reservation, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		return err
	}
	port := reservation.Addr().(*net.TCPAddr).Port
	if err := reservation.Close(); err != nil {
		return err
	}
	body := fmt.Sprintf(`application:
  name: meteor-test-dubbo-go-1
  metadataType: remote
protocols:
  dubbo:
    name: dubbo
    ip: 0.0.0.0
    port: %d
services:
  EchoService:
    interface: meteor.example.GoEcho
    protocol: dubbo
    group: compat
    version: "1.0"
    serialization: hessian2
shutdown_conf:
  timeout: 1s
  step_timeout: 100ms
`, port)
	path, err := configFile(opts.readyFile, body)
	if err != nil {
		return err
	}
	if err := config.ProviderInit(path); err != nil {
		return err
	}
	*config.GetBaseConfig() = config.GetProviderConfig().BaseConfig
	config.SetProviderService(&EchoProvider{})
	config.Load()

	// Readiness probes the real socket without issuing or retrying any business request.
	target := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	deadline := time.Now().Add(15 * time.Second)
	for {
		connection, dialError := net.DialTimeout("tcp", target, 100*time.Millisecond)
		if dialError == nil {
			connection.Close()
			break
		}
		if time.Now().After(deadline) {
			return errors.New("Dubbo-Go 1.5.9 listener did not start")
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err := writeFile(opts.readyFile, []byte("dubbo://"+target)); err != nil {
		return err
	}
	// config.Load installed the SDK's signal handler; it closes its own listener and exits.
	select {}
}

func call(opts options) error {
	// Use the 1.x generated proxy with direct Dubbo/Hessian2 and fail-fast delivery.
	body := fmt.Sprintf(`application:
  name: meteor-test-dubbo-go-1
  metadataType: remote
connect_timeout: 3s
request_timeout: 10s
check: false
references:
  EchoService:
    interface: meteor.example.GoEcho
    protocol: dubbo
    url: "dubbo://%s"
    group: compat
    version: "1.0"
    serialization: hessian2
    cluster: failfast
    loadbalance: random
    retries: "0"
`, strings.TrimPrefix(opts.target, "dubbo://"))
	path, err := configFile(opts.resultFile, body)
	if err != nil {
		return err
	}
	if err := config.ConsumerInit(path); err != nil {
		return err
	}
	*config.GetBaseConfig() = config.GetConsumerConfig().BaseConfig
	consumer := &EchoConsumer{}
	config.SetConsumerService(consumer)
	config.Load()
	defer extension.GetProtocol("dubbo").Destroy()

	// A single invocation validates either Unicode round-trip or the remote error marker.
	value := requestText
	if opts.scenario == "error" {
		value = "fail"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	reply, callError := consumer.Echo(ctx, value)
	if opts.scenario == "error" {
		if callError == nil || !strings.Contains(callError.Error(), "compat-error") {
			return fmt.Errorf("expected compat-error, got reply=%q error=%v", reply, callError)
		}
	} else {
		if callError != nil {
			return callError
		}
		if reply != requestText+"|meteor" {
			return fmt.Errorf("unexpected remote reply %q", reply)
		}
	}
	outcome := map[string]interface{}{"ok": true, "message": reply}
	if callError != nil {
		outcome["message"] = callError.Error()
	}
	encoded, err := json.Marshal(outcome)
	if err != nil {
		return err
	}
	return writeFile(opts.resultFile, encoded)
}

func main() {
	var opts options
	var tlsMode, serverName, certDir string
	flag.StringVar(&opts.role, "role", "", "server or client")
	flag.StringVar(&opts.target, "target", "", "direct provider host:port")
	flag.StringVar(&opts.readyFile, "ready-file", "", "selected provider address")
	flag.StringVar(&opts.resultFile, "result-file", "", "SDK result")
	flag.StringVar(&opts.scenario, "scenario", "unicode", "unicode or error")
	flag.StringVar(&opts.protocol, "protocol", "dubbo", "dubbo")
	flag.StringVar(&tlsMode, "tls-mode", "plain", "plain")
	flag.StringVar(&serverName, "server-name", "", "unused for plain TCP")
	flag.StringVar(&certDir, "cert-dir", "", "unused for plain TCP")
	flag.Parse()
	var err error
	switch {
	case tlsMode != "plain":
		err = fmt.Errorf("unsupported TLS mode %q", tlsMode)
	case opts.protocol != "dubbo":
		err = fmt.Errorf("unsupported protocol %q", opts.protocol)
	case opts.scenario != "unicode" && opts.scenario != "error":
		err = fmt.Errorf("unsupported scenario %q", opts.scenario)
	case opts.role == "server":
		err = serve(opts)
	case opts.role == "client":
		err = call(opts)
	default:
		err = fmt.Errorf("unsupported peer role %q", opts.role)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
