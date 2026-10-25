/* XML-RPC for C++ API: native clientSimple, registry and serverAbyss objects. */
#include <arpa/inet.h>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sys/socket.h>
#include <unistd.h>
#include <xmlrpc-c/base.hpp>
#include <xmlrpc-c/client_simple.hpp>
#include <xmlrpc-c/registry.hpp>
#include <xmlrpc-c/server_abyss.hpp>

class EchoMethod final : public xmlrpc_c::method {
public:
    EchoMethod() {
        this->_signature = "s:s";
        this->_help = "Echo one UTF-8 string.";
    }

    void execute(const xmlrpc_c::paramList &parameters, xmlrpc_c::value *result) override {
        const std::string text = parameters.getString(0);
        parameters.verifyEnd(1);
        *result = xmlrpc_c::value_string(text);
    }
};

static int listeningSocket(unsigned short &port) {
    int descriptor = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    if (descriptor < 0 || bind(descriptor, reinterpret_cast<sockaddr *>(&address), sizeof(address)) < 0) {
        throw std::runtime_error("Cannot bind XML-RPC C++ socket");
    }
    socklen_t size = sizeof(address);
    if (getsockname(descriptor, reinterpret_cast<sockaddr *>(&address), &size) < 0) {
        throw std::runtime_error("Cannot read XML-RPC C++ port");
    }
    port = ntohs(address.sin_port);
    return descriptor;
}

static void provide() {
    // The modern C++ SDK supplies the registry, type wrappers and HTTP server.
    xmlrpc_c::registry registry;
    registry.addMethod("demo.echo", xmlrpc_c::methodPtr(new EchoMethod));
    unsigned short port;
    int descriptor = listeningSocket(port);
    xmlrpc_c::serverAbyss server(xmlrpc_c::serverAbyss::constrOpt()
        .registryP(&registry).socketFd(descriptor).uriPath("/rpc"));

    // Publish the concrete container endpoint only after the SDK finishes setup.
    const char *host = std::getenv("METEOR_TEST_PEER_HOST");
    std::cout << "READY xmlrpc://" << (host == nullptr ? "127.0.0.1" : host)
              << ':' << port << "/default" << std::endl;
    server.run();
    close(descriptor);
}

static void consume(const std::string &text) {
    // clientSimple handles XML and HTTP; the peer only checks the business result.
    const char *target = std::getenv("METEOR_ORDER_TARGET");
    if (target == nullptr) {
        throw std::runtime_error("METEOR_ORDER_TARGET is required");
    }
    xmlrpc_c::clientSimple client;
    xmlrpc_c::value response;
    client.call(target, "demo.echo", "s", &response, text.c_str());
    std::string result = xmlrpc_c::value_string(response);
    if (result != text) {
        throw std::runtime_error("C++ echo mismatch: expected [" + text + "], received [" + result + "]");
    }
    std::cout << "OK xmlrpc-c C++: " << result << std::endl;
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
        std::cerr << "xmlrpc-c C++ failure: " << failure.what() << std::endl;
        return EXIT_FAILURE;
    }
}
