/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE.txt or https://www.apache.org/licenses/LICENSE-2.0
 */

#include "order.h"
#include "nxn_order.h"
#include <arpa/inet.h>
#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

extern void order_program_1(struct svc_req *, SVCXPRT *);

struct stored_order {
    OrderValue value;
    struct stored_order *next;
};

static struct stored_order *orders;

static struct stored_order *find_order(int id) {
    for (struct stored_order *order = orders; order != NULL; order = order->next) {
        if (order->value.id == id) {
            return order;
        }
    }
    return NULL;
}

OrderValue *order_save_1_svc(OrderValue *argument, struct svc_req *request) {
    (void) request;

    // Keep an independent in-memory copy; rpcgen frees the request after dispatch.
    struct stored_order *order = find_order(argument->id);
    if (order == NULL) {
        order = calloc(1, sizeof(*order));
        if (order == NULL) {
            return NULL;
        }
        order->next = orders;
        orders = order;
    }

    xdr_free((xdrproc_t) xdr_OrderValue, (char *) &order->value);
    order->value.id = argument->id;
    if (argument->description != NULL) {
        order->value.description = malloc(sizeof(OrderDescription));
        *order->value.description = strdup(*argument->description);
    }

    if (argument->attachment != NULL) {
        order->value.attachment = calloc(1, sizeof(OrderAttachment));
        u_int length = argument->attachment->OrderAttachment_len;
        order->value.attachment->OrderAttachment_len = length;
        if (length > 0) {
            order->value.attachment->OrderAttachment_val = malloc(length);
            memcpy(order->value.attachment->OrderAttachment_val,
                    argument->attachment->OrderAttachment_val, length);
        }
    }
    return &order->value;
}

OrderValue *order_find_1_svc(int *id, struct svc_req *request) {
    struct stored_order *order = find_order(*id);
    if (order == NULL) {
        svcerr_systemerr(request->rq_xprt);
        return NULL;
    }
    return &order->value;
}

int main(void) {
    // Resolve this scenario's listener; port 0 lets the OS choose an unused port.
    const char *host = getenv("METEOR_ORDER_BIND_HOST");
    const char *port = getenv("METEOR_ORDER_PORT");
    host = host == NULL ? "127.0.0.1" : host;
    port = port == NULL ? "8080" : port;
    struct addrinfo hints = {.ai_family = AF_INET, .ai_socktype = SOCK_STREAM, .ai_flags = AI_NUMERICSERV};
    struct addrinfo *resolved = NULL;
    int error = getaddrinfo(host, port, &hints, &resolved);
    if (error != 0) {
        fprintf(stderr, "Cannot resolve listener: %s\n", gai_strerror(error));
        return 2;
    }
    struct sockaddr_in address = *(struct sockaddr_in *) resolved->ai_addr;
    freeaddrinfo(resolved);
    int socket_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (socket_fd < 0) {
        perror("Cannot create listener");
        return 2;
    }

    int reuse = 1;
    setsockopt(socket_fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    if (bind(socket_fd, (struct sockaddr *) &address, sizeof(address)) != 0
            || listen(socket_fd, SOMAXCONN) != 0) {
        perror("Cannot open listener");
        close(socket_fd);
        return 2;
    }
    socklen_t address_length = sizeof(address);
    if (getsockname(socket_fd, (struct sockaddr *) &address, &address_length) != 0) {
        perror("Cannot obtain listener address");
        close(socket_fd);
        return 2;
    }

    // The transport owns the socket after creation.
    SVCXPRT *transport = svc_vc_create(socket_fd, 0, 0);
    if (transport == NULL) {
        fputs("Cannot create ONC RPC transport\n", stderr);
        close(socket_fd);
        return 2;
    }
    if (!svc_register(transport, ORDER_PROGRAM, ORDER_VERSION, order_program_1, 0)) {
        fputs("Cannot register ONC RPC service\n", stderr);
        svc_destroy(transport);
        return 2;
    }

    const char *publish_host = getenv("METEOR_ORDER_PUBLISH_HOST");
    publish_host = publish_host == NULL ? getenv("METEOR_TEST_PEER_HOST") : publish_host;
    publish_host = publish_host == NULL ? host : publish_host;
    if (nxn_order_enabled()) {
        nxn_order_ready("oncrpc", publish_host, ntohs(address.sin_port));
    } else {
        printf("Order provider is ready: oncrpc://%s:%u\n", publish_host, ntohs(address.sin_port));
        fflush(stdout);
    }

    // Dispatch save/find until the foreground process is stopped.
    svc_run();
    svc_destroy(transport);
    return 0;
}
