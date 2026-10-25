/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE.txt or https://www.apache.org/licenses/LICENSE-2.0
 */

#include "order.h"
#include <arpa/inet.h>
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
    // Bind the local TCP port; protocol 0 skips rpcbind registration.
    struct sockaddr_in address = {
        .sin_family = AF_INET,
        .sin_port = htons(8080),
        .sin_addr.s_addr = htonl(INADDR_LOOPBACK)
    };
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

    puts("Order provider is ready: oncrpc://127.0.0.1:8080");
    fflush(stdout);

    // Dispatch save/find until the foreground process is stopped.
    svc_run();
    svc_destroy(transport);
    return 0;
}
