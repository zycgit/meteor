/* Native gSOAP C++ proxy/service generated from the test-owned Echo WSDL. */
#include "soapEchoBindingProxy.h"
#include "soapEchoBindingService.h"
#include "EchoBinding.nsmap"
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <poll.h>
#include <unistd.h>

int EchoBindingService::echo(_ns2__echo *request, _ns2__echoResponse &response) {
    response.result = request->message;
    return SOAP_OK;
}

static int consume(const char *endpoint, const char *message) {
    /* Generated std::string binding owns XML escaping and SOAP response decoding. */
    EchoBindingProxy client(endpoint, SOAP_C_UTFSTRING);
    client.soap->connect_timeout = 5;
    client.soap->send_timeout = 10;
    client.soap->recv_timeout = 10;
    _ns2__echo request;
    _ns2__echoResponse response;
    request.message = message;
    if (client.echo(&request, response) != SOAP_OK) {
        client.soap_print_fault(stderr);
        return 1;
    }
    if (response.result != message) {
        std::fputs("gSOAP C++ echo mismatch\n", stderr);
        return 1;
    }
    std::puts("OK");
    return 0;
}

static int provide() {
    /* The generated C++ service owns its gSOAP context, listener and request data. */
    EchoBindingService service(SOAP_C_UTFSTRING);
    service.soap->send_timeout = 10;
    service.soap->recv_timeout = 10;
    SOAP_SOCKET listener = service.bind("0.0.0.0", 0, 8);
    if (!soap_valid_socket(listener)) {
        service.soap_print_fault(stderr);
        return 1;
    }
    sockaddr_in address;
    socklen_t length = sizeof(address);
    if (getsockname(listener, reinterpret_cast<sockaddr *>(&address), &length) != 0) {
        std::perror("getsockname");
        return 1;
    }
    std::printf("READY %u\n", ntohs(address.sin_port));
    std::fflush(stdout);

    /* stdin EOF releases this peer; no other language process or container is owned. */
    pollfd events[2] = {{STDIN_FILENO, POLLIN, 0}, {listener, POLLIN, 0}};
    for (;;) {
        if (poll(events, 2, -1) < 0) {
            if (errno == EINTR) {
                continue;
            }
            std::perror("poll");
            return 1;
        }
        if (events[0].revents != 0) {
            return 0;
        }
        if (events[1].revents != 0) {
            if (!soap_valid_socket(service.accept()) || service.serve() != SOAP_OK) {
                service.soap_print_fault(stderr);
                return 1;
            }
            service.destroy();
        }
    }
}

int main(int argc, char **argv) {
    if (argc == 2 && std::strcmp(argv[1], "provider") == 0) {
        return provide();
    }
    if (argc == 4 && std::strcmp(argv[1], "consumer") == 0) {
        return consume(argv[2], argv[3]);
    }
    std::fputs("Usage: peer provider | peer consumer URL MESSAGE\n", stderr);
    return 2;
}
