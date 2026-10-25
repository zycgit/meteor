/* XML-RPC for C API: one text echo, with independently executable roles. */
#include <arpa/inet.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <xmlrpc-c/base.h>
#include <xmlrpc-c/client.h>
#include <xmlrpc-c/server.h>
#include <xmlrpc-c/server_abyss.h>

static void require_success(const xmlrpc_env *env) {
    if (env->fault_occurred) {
        fprintf(stderr, "xmlrpc-c C fault %d: %s\n", env->fault_code, env->fault_string);
        exit(EXIT_FAILURE);
    }
}

static xmlrpc_value *echo(xmlrpc_env *env, xmlrpc_value *parameters, void *information) {
    (void) information;
    const char *text = NULL;
    xmlrpc_decompose_value(env, parameters, "(s)", &text);
    if (env->fault_occurred) {
        return NULL;
    }
    xmlrpc_value *result = xmlrpc_string_new(env, text);
    free((void *) text);
    return result;
}

static int listening_socket(unsigned short *port) {
    int descriptor = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    if (descriptor < 0 || bind(descriptor, (struct sockaddr *) &address, sizeof(address)) < 0) {
        perror("XML-RPC C socket");
        exit(EXIT_FAILURE);
    }
    socklen_t size = sizeof(address);
    if (getsockname(descriptor, (struct sockaddr *) &address, &size) < 0) {
        perror("XML-RPC C getsockname");
        exit(EXIT_FAILURE);
    }
    *port = ntohs(address.sin_port);
    return descriptor;
}

static void provide(void) {
    // The SDK owns HTTP framing and XML-RPC dispatch; the scenario owns this process.
    xmlrpc_env env;
    xmlrpc_env_init(&env);
    xmlrpc_server_abyss_global_init(&env);
    require_success(&env);
    xmlrpc_registry *registry = xmlrpc_registry_new(&env);
    require_success(&env);
    xmlrpc_registry_add_method(&env, registry, NULL, "demo.echo", &echo, NULL);
    require_success(&env);

    // Bind an OS-selected port before giving the socket to Abyss, without a port race.
    unsigned short port;
    int descriptor = listening_socket(&port);
    xmlrpc_server_abyss_parms options = {0};
    options.registryP = registry;
    options.socket_bound = 1;
    options.socket_handle = descriptor;
    options.uri_path = "/rpc";
    xmlrpc_server_abyss_t *server = NULL;
    xmlrpc_server_abyss_create(&env, &options, XMLRPC_APSIZE(uri_path), &server);
    require_success(&env);
    const char *host = getenv("METEOR_TEST_PEER_HOST");
    printf("READY xmlrpc://%s:%hu/default\n", host == NULL ? "127.0.0.1" : host, port);
    fflush(stdout);
    xmlrpc_server_abyss_run_server(&env, server);
    require_success(&env);

    xmlrpc_server_abyss_destroy(server);
    close(descriptor);
    xmlrpc_registry_free(registry);
    xmlrpc_server_abyss_global_term();
    xmlrpc_env_clean(&env);
}

static void consume(const char *text) {
    // The native client builds, sends and reads the XML-RPC message itself.
    const char *target = getenv("METEOR_ORDER_TARGET");
    if (target == NULL) {
        fprintf(stderr, "METEOR_ORDER_TARGET is required\n");
        exit(EXIT_FAILURE);
    }
    xmlrpc_env env;
    xmlrpc_env_init(&env);
    xmlrpc_client_init2(&env, XMLRPC_CLIENT_NO_FLAGS, "Meteor XML-RPC matrix", "1", NULL, 0);
    require_success(&env);
    xmlrpc_value *response = xmlrpc_client_call(&env, target, "demo.echo", "(s)", text);
    require_success(&env);
    const char *result = NULL;
    xmlrpc_read_string(&env, response, &result);
    require_success(&env);
    if (strcmp(text, result) != 0) {
        fprintf(stderr, "C echo mismatch: expected [%s], received [%s]\n", text, result);
        exit(EXIT_FAILURE);
    }
    printf("OK xmlrpc-c C: %s\n", result);
    free((void *) result);
    xmlrpc_DECREF(response);
    xmlrpc_client_cleanup();
    xmlrpc_env_clean(&env);
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "provider") == 0) {
        provide();
    } else if (argc == 3 && strcmp(argv[1], "consumer") == 0) {
        consume(argv[2]);
    } else {
        fprintf(stderr, "Usage: echo-peer provider | consumer text\n");
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}
