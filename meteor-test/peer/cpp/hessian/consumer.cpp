/* Aldratech hessiancpp 1.1.0: an independent SDK consumer over HTTP. */
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include "hessian_proxy.h"
#include "hessian_output.h"

static void inspect_http(const std::string &authority, const std::string &path, const char *message) {
    // Diagnose the original SDK transport in one request; this is never the compatibility assertion path.
    hessian::hessian_output output;
    std::string request = output.start_call("echo");
    hessian::wrappers::String argument(message);
    output.set_parameter(request, &argument);
    output.complete_call(request);
    sslpp::http_connection transport(authority, false);
    std::string reply = transport.POST(path, request, "application/binary", "hessian cpp");

    // Record the real status, headers and bytes without changing SDK sources or replacing its HTTP implementation.
    std::cout << "DIAGNOSTIC original SDK request Content-Type: application/binary\n";
    std::cout << "DIAGNOSTIC raw HTTP reply bytes=" << reply.size() << '\n';
    const std::string::size_type headerEnd = reply.find("\r\n\r\n");
    if (headerEnd != std::string::npos) {
        std::cout << reply.substr(0, headerEnd + 4);
    }
    std::cout << "DIAGNOSTIC raw reply hex:";
    for (unsigned char value : reply) {
        std::cout << ' ' << std::hex << std::setw(2) << std::setfill('0') << static_cast<unsigned int>(value);
    }
    std::cout << std::dec << std::endl;
}

int main(int argc, char **argv) {
    try {
        // This SDK accepts a host:port and a separate relative endpoint path.
        const bool inspect = argc == 3 && std::string(argv[1]) == "--inspect-http";
        if (argc != 2 && !inspect) {
            throw std::runtime_error("Usage: consumer [--inspect-http] TEXT");
        }
        const char *configured = std::getenv("METEOR_ORDER_TARGET");
        if (configured == NULL) {
            throw std::runtime_error("METEOR_ORDER_TARGET is required");
        }
        const std::string target(configured);
        const std::string prefix("http://");
        const std::string::size_type path = target.find('/', prefix.size());
        if (target.compare(0, prefix.size(), prefix) != 0 || path == std::string::npos) {
            throw std::runtime_error("The hessiancpp source SDK fixture requires an HTTP endpoint");
        }
        const std::string authority = target.substr(prefix.size(), path - prefix.size());
        if (inspect) {
            inspect_http(authority, target.substr(path), argv[2]);
            return EXIT_SUCCESS;
        }
        hessian::hessian_proxy client(authority, target.substr(path), false);

        // The SDK builds the Hessian 1 request and parses its typed reply.
        hessian::wrappers::String argument(argv[1]);
        std::unique_ptr<hessian::wrappers::Object> result(client.call("echo", 1, &argument));
        hessian::wrappers::String *text = dynamic_cast<hessian::wrappers::String *>(result.get());
        if (text == NULL) {
            throw std::runtime_error("hessiancpp response is not a string");
        }
        const std::string actual = hessian::wrappers::String::to_utf8_string(text->wvalue());
        if (actual != argv[1]) {
            throw std::runtime_error("hessiancpp echo response did not match the request");
        }
        std::cout << "OK hessiancpp: " << actual << std::endl;
        return EXIT_SUCCESS;
    } catch (const std::exception &failure) {
        std::cerr << "hessiancpp failure: " << failure.what() << std::endl;
        return EXIT_FAILURE;
    }
}
