/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE.txt or https://www.apache.org/licenses/LICENSE-2.0
 */
#include "order.h"
#include <arpa/inet.h>
#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>

static void print_string(const char *text) {
    putchar('"');
    for (const unsigned char *cursor = (const unsigned char *) text; *cursor != '\0'; cursor++) {
        if (*cursor == '"' || *cursor == '\\') {
            putchar('\\');
            putchar(*cursor);
        } else if (*cursor < 32) {
            printf("\\u%04x", *cursor);
        } else {
            putchar(*cursor);
        }
    }
    putchar('"');
}

static void print_order(OrderValue *order) {
    printf("{\"id\":%d,\"description\":", order->id);
    if (order->description == NULL) {
        fputs("null", stdout);
    } else {
        print_string(*order->description);
    }

    fputs(",\"attachment\":", stdout);
    if (order->attachment == NULL) {
        fputs("null", stdout);
    } else {
        putchar('[');
        for (u_int index = 0; index < order->attachment->OrderAttachment_len; index++) {
            printf("%s%u", index == 0 ? "" : ",",
                    (unsigned char) order->attachment->OrderAttachment_val[index]);
        }
        putchar(']');
    }
    puts("}");
}

int main(void) {
    // Resolve the provider URI supplied by the scenario, without rpcbind.
    const char *target = getenv("METEOR_ORDER_TARGET");
    target = target == NULL ? "oncrpc://127.0.0.1:8080" : target;
    char host[256];
    char port[6];
    int consumed = 0;
    int fields = sscanf(target, "oncrpc://%255[^:/]:%5[0-9]%n", host, port, &consumed);
    if (fields != 2 || target[consumed] != '\0' || atoi(port) < 1 || atoi(port) > 65535) {
        fputs("METEOR_ORDER_TARGET must be an oncrpc://host:port URI\n", stderr);
        return 2;
    }
    struct addrinfo hints = {.ai_family = AF_INET, .ai_socktype = SOCK_STREAM, .ai_flags = AI_NUMERICSERV};
    struct addrinfo *resolved = NULL;
    int error = getaddrinfo(host, port, &hints, &resolved);
    if (error != 0) {
        fprintf(stderr, "Cannot resolve provider: %s\n", gai_strerror(error));
        return 2;
    }
    struct sockaddr_in address = *(struct sockaddr_in *) resolved->ai_addr;
    freeaddrinfo(resolved);

    int socket_fd = RPC_ANYSOCK;
    CLIENT *client = clnttcp_create(&address, ORDER_PROGRAM, ORDER_VERSION, &socket_fd, 0, 0);
    if (client == NULL) {
        clnt_pcreateerror("Cannot connect to order provider");
        return 2;
    }

    // Save the ordinary order DTO, then read it back through the same published contract.
    int id = 1001;
    char *description = "meteor-order";
    char attachment_text[] = "Meteor";
    OrderAttachment attachment = {
        .OrderAttachment_len = sizeof(attachment_text) - 1,
        .OrderAttachment_val = attachment_text
    };
    OrderValue input = {.id = id, .description = &description, .attachment = &attachment};
    OrderValue *saved = order_save_1(&input, client);
    if (saved == NULL) {
        clnt_perror(client, "Cannot save order");
        clnt_destroy(client);
        return 2;
    }

    xdr_free((xdrproc_t) xdr_OrderValue, (char *) saved);
    OrderValue *found = order_find_1(&id, client);
    if (found == NULL) {
        clnt_perror(client, "Cannot find order");
        clnt_destroy(client);
        return 2;
    }

    print_order(found);
    xdr_free((xdrproc_t) xdr_OrderValue, (char *) found);
    clnt_destroy(client);
    return 0;
}
