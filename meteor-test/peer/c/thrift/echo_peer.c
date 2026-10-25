/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
#include "echo_service.h"
#include <thrift/c_glib/protocol/thrift_binary_protocol.h>
#include <thrift/c_glib/protocol/thrift_binary_protocol_factory.h>
#include <thrift/c_glib/server/thrift_simple_server.h>
#include <thrift/c_glib/transport/thrift_framed_transport.h>
#include <thrift/c_glib/transport/thrift_framed_transport_factory.h>
#include <thrift/c_glib/transport/thrift_server_socket.h>
#include <thrift/c_glib/transport/thrift_socket.h>
#include <arpa/inet.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

/* The generated abstract handler is extended only with the ordinary echo business method. */
typedef struct { EchoServiceHandler parent; } CEchoHandler;
typedef struct { EchoServiceHandlerClass parent; } CEchoHandlerClass;
G_DEFINE_TYPE(CEchoHandler, c_echo_handler, TYPE_ECHO_SERVICE_HANDLER)

static gboolean echo_value(EchoServiceIf *handler, gchar **result, const gchar *value, GError **error) {
    (void) handler;
    (void) error;
    *result = g_strdup(value);
    return TRUE;
}

static void c_echo_handler_class_init(CEchoHandlerClass *type) {
    ECHO_SERVICE_HANDLER_CLASS(type)->echo = echo_value;
}

static void c_echo_handler_init(CEchoHandler *handler) {
    (void) handler;
}

/* GLib 0.17 exposes no selected-port accessor; this listener adapter only announces readiness. */
typedef struct { ThriftServerSocket parent; } CReadySocket;
typedef struct { ThriftServerSocketClass parent; } CReadySocketClass;
G_DEFINE_TYPE(CReadySocket, c_ready_socket, THRIFT_TYPE_SERVER_SOCKET)

static gboolean listen_and_announce(ThriftServerTransport *transport, GError **error) {
    ThriftServerTransportClass *parent = THRIFT_SERVER_TRANSPORT_CLASS(c_ready_socket_parent_class);
    if (!parent->listen(transport, error)) {
        return FALSE;
    }

    /* Obtain the already-bound port without guessing readiness or reserving a competing socket. */
    struct sockaddr_in address;
    socklen_t length = sizeof(address);
    if (getsockname(THRIFT_SERVER_SOCKET(transport)->sd, (struct sockaddr *) &address, &length) != 0) {
        g_set_error_literal(error, G_FILE_ERROR, G_FILE_ERROR_FAILED, "Cannot read the C GLib listener port");
        return FALSE;
    }
    const char *host = getenv("METEOR_TEST_PEER_HOST");
    host = host == NULL ? "127.0.0.1" : host;
    printf("Order provider is ready: thrift://%s:%u\n", host, ntohs(address.sin_port));
    fflush(stdout);
    return TRUE;
}

static void c_ready_socket_class_init(CReadySocketClass *type) {
    THRIFT_SERVER_TRANSPORT_CLASS(type)->listen = listen_and_announce;
}

static void c_ready_socket_init(CReadySocket *socket) {
    (void) socket;
}

static gboolean provide(GError **error) {
    /* Official GLib Processor, BinaryProtocol and FramedTransport own all RPC encoding and dispatch. */
    GObject *handler = g_object_new(c_echo_handler_get_type(), NULL);
    GObject *processor = g_object_new(TYPE_ECHO_SERVICE_PROCESSOR, "handler", handler, NULL);
    GObject *listener = g_object_new(c_ready_socket_get_type(), "port", 0, NULL);
    GObject *transports = g_object_new(THRIFT_TYPE_FRAMED_TRANSPORT_FACTORY, NULL);
    GObject *protocols = g_object_new(THRIFT_TYPE_BINARY_PROTOCOL_FACTORY, NULL);
    GObject *server = g_object_new(THRIFT_TYPE_SIMPLE_SERVER,
        "processor", processor, "server_transport", listener,
        "input_transport_factory", transports, "output_transport_factory", transports,
        "input_protocol_factory", protocols, "output_protocol_factory", protocols, NULL);

    /* This basic matrix cell makes one echo call on one connection; it does not test concurrency. */
    gboolean success = thrift_server_serve(THRIFT_SERVER(server), error);
    g_object_unref(server);
    g_object_unref(protocols);
    g_object_unref(transports);
    g_object_unref(listener);
    g_object_unref(processor);
    g_object_unref(handler);
    return success;
}

static gboolean consume(GError **error) {
    const char *target = getenv("METEOR_ORDER_TARGET");
    if (target == NULL) {
        g_set_error_literal(error, G_URI_ERROR, G_URI_ERROR_FAILED, "METEOR_ORDER_TARGET is required");
        return FALSE;
    }
    GUri *address = g_uri_parse(target, G_URI_FLAGS_NONE, error);
    if (address == NULL) {
        return FALSE;
    }

    /* The generated SDK client uses a real TCP transport, with no Java codec or protocol proxy. */
    GObject *socket = g_object_new(THRIFT_TYPE_SOCKET,
        "hostname", g_uri_get_host(address), "port", g_uri_get_port(address), NULL);
    GObject *transport = g_object_new(THRIFT_TYPE_FRAMED_TRANSPORT, "transport", socket, NULL);
    GObject *protocol = g_object_new(THRIFT_TYPE_BINARY_PROTOCOL, "transport", transport, NULL);
    GObject *client = g_object_new(TYPE_ECHO_SERVICE_CLIENT,
        "input_protocol", protocol, "output_protocol", protocol, NULL);
    gboolean success = thrift_transport_open(THRIFT_TRANSPORT(transport), error);
    const char *expected = "C GLib Thrift 中文";
    gchar *actual = NULL;
    if (success) {
        success = echo_service_client_echo(ECHO_SERVICE_IF(client), &actual, expected, error);
    }

    /* The returned UTF-8 string is the single basic interoperability assertion for this direction. */
    if (success && g_strcmp0(expected, actual) != 0) {
        g_set_error_literal(error, G_FILE_ERROR, G_FILE_ERROR_FAILED, "C GLib echo returned a different value");
        success = FALSE;
    }
    if (success) {
        printf("RESULT %s\n", actual);
    }
    if (thrift_transport_is_open(THRIFT_TRANSPORT(transport))) {
        thrift_transport_close(THRIFT_TRANSPORT(transport), NULL);
    }
    g_free(actual);
    g_object_unref(client);
    g_object_unref(protocol);
    g_object_unref(transport);
    g_object_unref(socket);
    g_uri_unref(address);
    return success;
}

int main(int argc, char **argv) {
    GError *error = NULL;
    gboolean success = FALSE;
    if (argc == 2 && strcmp(argv[1], "provider") == 0) {
        success = provide(&error);
    } else if (argc == 2 && strcmp(argv[1], "consumer") == 0) {
        success = consume(&error);
    }
    if (!success) {
        fprintf(stderr, "%s\n", error == NULL ? "Expected provider or consumer role" : error->message);
        g_clear_error(&error);
        return 1;
    }
    return 0;
}
