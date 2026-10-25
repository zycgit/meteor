// The upstream SunRPC codec is pinned by commit in this independent preview SDK module.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/rpc"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/prashanthpai/sunrpc"
)

const requestText = "Go SunRPC to Meteor"

type options struct {
	role, target, readyFile, resultFile string
}

type EchoService struct{}

func (*EchoService) Echo(value string, reply *string) error {
	*reply = value + "|go-oncrpc"
	return nil
}

func registerProcedure() error {
	// The existing ONC interop.x contract fixes program 0x31234567, version 1, echo procedure 1.
	procedure := sunrpc.Procedure{
		ID:   sunrpc.ProcedureID{ProgramNumber: 0x31234567, ProgramVersion: 1, ProcedureNumber: 1},
		Name: "Echo.Echo",
	}
	return sunrpc.RegisterProcedure(procedure, true)
}

func writeFile(path string, content []byte) error {
	if err := os.WriteFile(path+".part", content, 0600); err != nil {
		return err
	}
	return os.Rename(path+".part", path)
}

func serve(opts options) error {
	// The SDK registers the normal Go method; the codec owns all ONC framing and XDR encoding.
	server := rpc.NewServer()
	if err := server.RegisterName("Echo", &EchoService{}); err != nil {
		return err
	}
	listener, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port
	address := fmt.Sprintf("tcp://127.0.0.1:%d", port)
	if err := writeFile(opts.readyFile, []byte(address)); err != nil {
		return err
	}

	// Only this process owns the listener; cancellation closes it and releases the accept loop.
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	go func() {
		<-ctx.Done()
		listener.Close()
	}()
	for {
		connection, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return nil
			}
			return err
		}
		go server.ServeCodec(sunrpc.NewServerCodec(connection, nil))
	}
}

func call(opts options) error {
	// This is a direct TCP call, without rpcbind, retries, or a handwritten protocol implementation.
	connection, err := net.DialTimeout("tcp", opts.target, 10*time.Second)
	if err != nil {
		return err
	}
	if err := connection.SetDeadline(time.Now().Add(10 * time.Second)); err != nil {
		connection.Close()
		return err
	}
	client := sunrpc.NewClient(connection)
	defer client.Close()
	var reply string
	if err := client.Call("Echo.Echo", requestText, &reply); err != nil {
		return err
	}

	// A provider-specific suffix proves that the remote Meteor implementation executed.
	if reply != requestText+"|meteor" {
		return fmt.Errorf("unexpected remote reply %q", reply)
	}
	result, err := json.Marshal(map[string]any{"ok": true, "message": reply})
	if err != nil {
		return err
	}
	return writeFile(opts.resultFile, result)
}

func main() {
	var opts options
	flag.StringVar(&opts.role, "role", "", "server or client")
	flag.StringVar(&opts.target, "target", "", "direct provider host:port")
	flag.StringVar(&opts.readyFile, "ready-file", "", "write the provider address")
	flag.StringVar(&opts.resultFile, "result-file", "", "write the successful SDK result")
	flag.Parse()
	err := registerProcedure()
	if err == nil {
		switch opts.role {
		case "server":
			err = serve(opts)
		case "client":
			err = call(opts)
		default:
			err = fmt.Errorf("unsupported peer role %q", opts.role)
		}
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
