// Apache Dubbo-Go is isolated from the existing Go order-peer dependency graph.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"dubbo.apache.org/dubbo-go/v3/client"
	_ "dubbo.apache.org/dubbo-go/v3/cluster/cluster/failfast"
	_ "dubbo.apache.org/dubbo-go/v3/cluster/loadbalance/random"
	"dubbo.apache.org/dubbo-go/v3/common"
	"dubbo.apache.org/dubbo-go/v3/common/constant"
	"dubbo.apache.org/dubbo-go/v3/common/extension"
	_ "dubbo.apache.org/dubbo-go/v3/filter/echo"
	_ "dubbo.apache.org/dubbo-go/v3/filter/graceful_shutdown"
	"dubbo.apache.org/dubbo-go/v3/global"
	"dubbo.apache.org/dubbo-go/v3/protocol"
	_ "dubbo.apache.org/dubbo-go/v3/protocol/dubbo"
	_ "dubbo.apache.org/dubbo-go/v3/protocol/triple"
	_ "dubbo.apache.org/dubbo-go/v3/proxy/proxy_factory"
	"dubbo.apache.org/dubbo-go/v3/server"
)

const (
	serviceName    = "meteor.example.GoEcho"
	serviceGroup   = "compat"
	serviceVersion = "1.0"
	requestText    = "Dubbo-Go → Meteor 中文🙂"
)

type options struct {
	protocol, role, target, readyFile, resultFile, scenario string
}

type EchoService interface {
	Echo(context.Context, string) (string, error)
}

type EchoProvider struct{ protocol string }

func (p *EchoProvider) Echo(_ context.Context, value string) (string, error) {
	if value == "fail" {
		return "", errors.New("compat-error: fail")
	}
	return value + "|go-" + p.protocol, nil
}

func sdkProtocol(selected string) string {
	if selected == "triple" {
		return "tri"
	}
	return selected
}

func writeFile(path string, content []byte) error {
	if err := os.WriteFile(path+".part", content, 0600); err != nil {
		return err
	}
	return os.Rename(path+".part", path)
}

func serve(opts options) error {
	// The SDK owns its listener; choose an available port in this peer container before export.
	reservation, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		return err
	}
	port := reservation.Addr().(*net.TCPAddr).Port
	if err := reservation.Close(); err != nil {
		return err
	}
	scheme := sdkProtocol(opts.protocol)
	application := global.DefaultApplicationConfig()
	application.Name = "meteor-test-go"
	// Direct calls need no metadata RPC listener; no remote report is configured without registries.
	application.MetadataType = constant.RemoteMetadataStorageType
	provider, err := server.NewServer(
		server.SetServerApplication(application),
		server.WithServerProtocol(protocol.WithID(scheme), protocol.WithProtocol(scheme),
			protocol.WithIp("0.0.0.0"), protocol.WithPort(port)),
		server.WithServerNotRegister(), server.WithServerGroup(serviceGroup),
		server.WithServerVersion(serviceVersion), server.WithServerSerialization(constant.Hessian2Serialization),
		server.WithServerFilter("echo"),
	)
	if err != nil {
		return err
	}

	// ServiceInfo fixes the lowercase wire method independently of the exported Go method name.
	info := &common.ServiceInfo{
		InterfaceName: serviceName,
		ServiceType:   (*EchoService)(nil),
		Methods: []common.MethodInfo{{
			Name: "echo", Type: constant.CallUnary,
			ReqInitFunc: func() any { return []any{new(string)} },
			MethodFunc: func(ctx context.Context, args []any, handler any) (any, error) {
				return handler.(EchoService).Echo(ctx, args[0].(string))
			},
		}},
	}
	if err := provider.Register(&EchoProvider{protocol: opts.protocol}, info); err != nil {
		return err
	}

	// Serving and shutdown belong to this process; readiness waits for the SDK's actual socket.
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- provider.ServeContext(ctx) }()
	target := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	deadline := time.NewTimer(15 * time.Second)
	defer deadline.Stop()
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case failure := <-done:
			return failure
		case <-deadline.C:
			return errors.New("Dubbo-Go provider did not start its listener")
		case <-ticker.C:
			connection, dialError := net.DialTimeout("tcp", target, 100*time.Millisecond)
			if dialError != nil {
				continue
			}
			connection.Close()
			if err := writeFile(opts.readyFile, []byte(scheme+"://"+target)); err != nil {
				return err
			}
			select {
			case failure := <-done:
				if ctx.Err() != nil {
					return nil
				}
				return failure
			case <-ctx.Done():
				return nil
			}
		}
	}
}

func call(opts options) error {
	// The official SDK selects the wire protocol and Hessian2, with retries disabled for this check.
	protocolOption := client.WithClientProtocolDubbo()
	if opts.protocol == "triple" {
		protocolOption = client.WithClientProtocolTriple()
	}
	consumer, err := client.NewClient(protocolOption, client.WithClientURL(opts.target),
		client.WithClientGroup(serviceGroup), client.WithClientVersion(serviceVersion),
		client.WithClientSerialization(constant.Hessian2Serialization), client.WithClientRetries(0),
		client.WithClientClusterFailFast(), client.WithClientLoadBalanceRandom(),
		client.WithClientFilter("echo"), client.WithClientRequestTimeout(10*time.Second))
	if err != nil {
		return err
	}
	defer extension.GetProtocol(sdkProtocol(opts.protocol)).Destroy()
	info := &client.ClientInfo{InterfaceName: serviceName, MethodNames: []string{"echo"}}
	connection, err := consumer.DialWithInfo(serviceName, info)
	if err != nil {
		return err
	}

	// One scalar call must return the suffix produced by the Meteor provider.
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	var reply string
	request := requestText
	if opts.scenario == "error" {
		request = "fail"
	}
	failure := connection.CallUnary(ctx, []any{request}, &reply, "echo", client.WithCallRetries(0),
		client.WithCallRequestTimeout(10*time.Second))
	if opts.scenario == "error" {
		if failure == nil || !strings.Contains(failure.Error(), "compat-error") {
			return fmt.Errorf("expected remote compat-error, received %v", failure)
		}
		encoded, err := json.Marshal(map[string]any{"ok": true, "message": failure.Error()})
		if err != nil {
			return err
		}
		return writeFile(opts.resultFile, encoded)
	}
	if failure != nil {
		return failure
	}
	if reply != requestText+"|meteor" {
		return fmt.Errorf("unexpected remote reply %q", reply)
	}
	outcome := map[string]any{"ok": true, "message": reply}
	encoded, err := json.Marshal(outcome)
	if err != nil {
		return err
	}
	return writeFile(opts.resultFile, encoded)
}

func main() {
	var opts options
	flag.StringVar(&opts.protocol, "protocol", "", "dubbo or triple")
	flag.StringVar(&opts.role, "role", "", "server or client")
	flag.StringVar(&opts.target, "target", "", "direct provider host:port")
	flag.StringVar(&opts.readyFile, "ready-file", "", "write the selected provider address")
	flag.StringVar(&opts.resultFile, "result-file", "", "write a successful SDK result")
	flag.StringVar(&opts.scenario, "scenario", "unicode", "unicode or error")
	flag.String("tls-mode", "plain", "shared harness mode; version baseline uses plain TCP")
	flag.String("server-name", "", "shared harness TLS identity")
	flag.String("cert-dir", "", "shared harness certificate directory")
	flag.Parse()
	var err error
	switch opts.role {
	case "server":
		err = serve(opts)
	case "client":
		err = call(opts)
	default:
		err = fmt.Errorf("unsupported peer role %q", opts.role)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
