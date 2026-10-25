/* libjson-rpc-cpp JSON-RPC 2.0 client and server, each in an independent role. */
#include <arpa/inet.h>
#include <cstdlib>
#include <iostream>
#include <pthread.h>
#include <signal.h>
#include <stdexcept>
#include <string>
#include <sys/socket.h>
#include <unistd.h>
#include <jsonrpccpp/client.h>
#include <jsonrpccpp/client/connectors/httpclient.h>
#include <jsonrpccpp/server.h>
#include <jsonrpccpp/server/connectors/httpserver.h>

class EchoServer final : public jsonrpc::AbstractServer<EchoServer> {
public:
    explicit EchoServer(jsonrpc::HttpServer &connection)
        : jsonrpc::AbstractServer<EchoServer>(connection, jsonrpc::JSONRPC_SERVER_V2) {
        // The SDK validates positional parameters and generates JSON-RPC responses.
        jsonrpc::Procedure method("demo.echo", jsonrpc::PARAMS_BY_POSITION,
                                 jsonrpc::JSON_STRING, "text", jsonrpc::JSON_STRING, NULL);
        this->bindAndAddMethod(method, &EchoServer::echo);
    }

    void echo(const Json::Value &parameters, Json::Value &result) {
        result = parameters[0u].asString();
    }
};

static unsigned short availablePort() {
    // HttpServer cannot adopt a bound socket or expose its port when configured with zero.
    int descriptor = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    if (descriptor < 0 || bind(descriptor, reinterpret_cast<sockaddr *>(&address), sizeof(address)) < 0) {
        throw std::runtime_error("Cannot allocate JSON-RPC C++ port");
    }
    socklen_t size = sizeof(address);
    if (getsockname(descriptor, reinterpret_cast<sockaddr *>(&address), &size) < 0) {
        throw std::runtime_error("Cannot read JSON-RPC C++ port");
    }
    const unsigned short port = ntohs(address.sin_port);
    close(descriptor);
    return port;
}

static void provide() {
    // Block lifecycle signals before the SDK creates its worker, then wait without polling.
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGINT);
    sigaddset(&signals, SIGTERM);
    if (pthread_sigmask(SIG_BLOCK, &signals, NULL) != 0) {
        throw std::runtime_error("Cannot prepare JSON-RPC C++ shutdown");
    }

    // SDK startup failures remain failures; the peer never retries a failed bind.
    const unsigned short port = availablePort();
    jsonrpc::HttpServer connection(port, "", "", 1);
    EchoServer server(connection);
    if (!server.StartListening()) {
        throw std::runtime_error("Cannot start libjson-rpc-cpp HttpServer");
    }
    const char *host = std::getenv("METEOR_TEST_PEER_HOST");
    std::cout << "READY jsonrpc://" << (host == nullptr ? "127.0.0.1" : host)
              << ':' << port << "/default" << std::endl;

    // The test process owns this server; cancellation releases only its SDK listener.
    int received;
    int status = sigwait(&signals, &received);
    server.StopListening();
    if (status != 0) {
        throw std::runtime_error("Cannot wait for JSON-RPC C++ shutdown");
    }
}

static void consume(const std::string &text) {
    // HttpClient and Client V2 handle HTTP, request IDs and response validation.
    const char *target = std::getenv("METEOR_ORDER_TARGET");
    if (target == nullptr) {
        throw std::runtime_error("METEOR_ORDER_TARGET is required");
    }
    jsonrpc::HttpClient connection(target);
    connection.SetTimeout(10000);
    jsonrpc::Client client(connection, jsonrpc::JSONRPC_CLIENT_V2);
    Json::Value parameters(Json::arrayValue);
    parameters.append(text);
    Json::Value result = client.CallMethod("demo.echo", parameters);
    if (!result.isString() || result.asString() != text) {
        throw std::runtime_error("C++ JSON-RPC echo mismatch: " + result.toStyledString());
    }
    std::cout << "OK libjson-rpc-cpp: " << result.asString() << std::endl;
}

int main(int argc, char **argv) {
    try {
        if (argc == 2 && std::string(argv[1]) == "provider") {
            provide();
        } else if (argc == 3 && std::string(argv[1]) == "consumer") {
            consume(argv[2]);
        } else {
            throw std::runtime_error("Usage: echo-peer provider | consumer text");
        }
        return EXIT_SUCCESS;
    } catch (const std::exception &failure) {
        std::cerr << "libjson-rpc-cpp failure: " << failure.what() << std::endl;
        return EXIT_FAILURE;
    }
}
