/* gSOAP C client/server; SOAP framing and XML mapping are generated from WSDL. */
#include "soapH.h"
#include "EchoBinding.nsmap"
#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int __ns1__echo(struct soap *soap, struct _ns2__echo *request, struct _ns2__echoResponse *response) {
    response->result = soap_strdup(soap, request->message);
    return response->result == NULL ? SOAP_EOM : SOAP_OK;
}

static int consume(struct soap *soap, const char *endpoint, const char *message) {
    /* The generated client owns HTTP, SOAP Action, namespaces and XML escaping. */
    struct _ns2__echo request;
    struct _ns2__echoResponse response;
    soap_default__ns2__echo(soap, &request);
    soap_default__ns2__echoResponse(soap, &response);
    request.message = (char *) message;
    if (soap_call___ns1__echo(soap, endpoint, NULL, &request, &response) != SOAP_OK) {
        soap_print_fault(soap, stderr);
        return 1;
    }
    if (response.result == NULL || strcmp(message, response.result) != 0) {
        fprintf(stderr, "gSOAP C echo mismatch\n");
        return 1;
    }
    puts("OK");
    return 0;
}

static int provide(struct soap *soap) {
    /* Bind an isolated ephemeral port; stdin EOF ends only this test process. */
    SOAP_SOCKET listener = soap_bind(soap, "0.0.0.0", 0, 8);
    if (!soap_valid_socket(listener)) {
        soap_print_fault(soap, stderr);
        return 1;
    }
    struct sockaddr_in address;
    socklen_t length = sizeof(address);
    if (getsockname(listener, (struct sockaddr *) &address, &length) != 0) {
        perror("getsockname");
        return 1;
    }
    printf("READY %u\n", ntohs(address.sin_port));
    fflush(stdout);

    /* gSOAP accepts and dispatches each request using its generated service. */
    struct pollfd events[2] = {{STDIN_FILENO, POLLIN, 0}, {listener, POLLIN, 0}};
    for (;;) {
        if (poll(events, 2, -1) < 0) {
            if (errno == EINTR) {
                continue;
            }
            perror("poll");
            return 1;
        }
        if (events[0].revents != 0) {
            return 0;
        }
        if (events[1].revents != 0) {
            if (!soap_valid_socket(soap_accept(soap)) || soap_serve(soap) != SOAP_OK) {
                soap_print_fault(soap, stderr);
                return 1;
            }
            soap_destroy(soap);
            soap_end(soap);
        }
    }
}

int main(int argc, char **argv) {
    struct soap soap;
    soap_init1(&soap, SOAP_C_UTFSTRING);
    soap.connect_timeout = 5;
    soap.send_timeout = 10;
    soap.recv_timeout = 10;

    /* Only role and endpoint control are local; business traffic is native gSOAP. */
    int result;
    if (argc == 2 && strcmp(argv[1], "provider") == 0) {
        result = provide(&soap);
    } else if (argc == 4 && strcmp(argv[1], "consumer") == 0) {
        result = consume(&soap, argv[2], argv[3]);
    } else {
        fprintf(stderr, "Usage: peer provider | peer consumer URL MESSAGE\n");
        result = 2;
    }
    soap_destroy(&soap);
    soap_end(&soap);
    soap_done(&soap);
    return result;
}
