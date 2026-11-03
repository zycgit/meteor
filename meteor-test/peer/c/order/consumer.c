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
    struct nxn_order_input nxn_input = {0};
    if (nxn_order_enabled() && !nxn_order_read(&nxn_input)) {
        clnt_destroy(client);
        return 2;
    }
    int id = nxn_order_enabled() ? nxn_input.id : 1001;
    char *description = nxn_order_enabled() ? (char *) nxn_input.description : "meteor-order";
    char attachment_text[] = "Meteor";
    OrderAttachment attachment = {
        .OrderAttachment_len = nxn_order_enabled() ? (u_int) nxn_input.attachment_length : sizeof(attachment_text) - 1,
        .OrderAttachment_val = nxn_order_enabled() ? (char *) nxn_input.attachment : attachment_text
    };
    OrderValue input = {.id = id, .description = &description, .attachment = &attachment};
    OrderValue *saved = order_save_1(&input, client);
    if (saved == NULL) {
        clnt_perror(client, "Cannot save order");
        free(nxn_input.attachment);
        clnt_destroy(client);
        return 2;
    }
    if (nxn_order_enabled() && (saved->id != input.id || saved->description == NULL
            || saved->attachment == NULL || strcmp(*saved->description, description) != 0
            || saved->attachment->OrderAttachment_len != attachment.OrderAttachment_len
            || memcmp(saved->attachment->OrderAttachment_val, attachment.OrderAttachment_val,
                    attachment.OrderAttachment_len) != 0)) {
        fputs("ONC RPC save returned a different NXN order\n", stderr);
        xdr_free((xdrproc_t) xdr_OrderValue, (char *) saved);
        free(nxn_input.attachment);
        clnt_destroy(client);
        return 2;
    }

    if (!nxn_order_enabled()) {
        xdr_free((xdrproc_t) xdr_OrderValue, (char *) saved);
    }
    OrderValue *found = order_find_1(&id, client);
    if (found == NULL) {
        clnt_perror(client, "Cannot find order");
        if (nxn_order_enabled()) {
            xdr_free((xdrproc_t) xdr_OrderValue, (char *) saved);
        }
        free(nxn_input.attachment);
        clnt_destroy(client);
        return 2;
    }

    if (nxn_order_enabled()) {
        if (found->description == NULL || found->attachment == NULL) {
            fputs("NXN order response is missing description or attachment\n", stderr);
            xdr_free((xdrproc_t) xdr_OrderValue, (char *) found);
            xdr_free((xdrproc_t) xdr_OrderValue, (char *) saved);
            free(nxn_input.attachment);
            clnt_destroy(client);
            return 2;
        }
        // rpcgen owns separate response storage for save and find; keep both actual RPC results alive.
        nxn_order_result(saved->id, *saved->description,
                (const unsigned char *) saved->attachment->OrderAttachment_val,
                saved->attachment->OrderAttachment_len, found->id, *found->description,
                (const unsigned char *) found->attachment->OrderAttachment_val,
                found->attachment->OrderAttachment_len);
        xdr_free((xdrproc_t) xdr_OrderValue, (char *) saved);
    } else {
        print_order(found);
    }
    xdr_free((xdrproc_t) xdr_OrderValue, (char *) found);
    free(nxn_input.attachment);
    clnt_destroy(client);
    return 0;
}
