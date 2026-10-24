/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */

#include "generated/interop.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netinet/in.h>
#include <netdb.h>
#include <poll.h>
#include <rpc/auth_unix.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

extern void onc_interop_1(struct svc_req *, SVCXPRT *);

struct peer_options {
    const char *command;
    const char *transport;
    const char *host;
    const char *scenario;
    const char *value;
    const char *auth;
    unsigned short port;
};

static int counter;

/* The generated dispatcher sends these borrowed argument objects before freeing them. */
onc_text *onc_echo_1_svc(onc_text *argument, struct svc_req *request) {
    (void) request;
    return argument;
}

int *onc_add_1_svc(onc_pair *argument, struct svc_req *request) {
    static int result;
    (void) request;
    result = argument->left * 100 + argument->right;
    return &result;
}

onc_sample *onc_mirror_1_svc(onc_sample *argument, struct svc_req *request) {
    (void) request;
    return argument;
}

void *onc_ping_1_svc(void *argument, struct svc_req *request) {
    static char result;
    (void) argument;
    (void) request;
    return &result;
}

void *onc_crash_1_svc(void *argument, struct svc_req *request) {
    (void) argument;
    svcerr_systemerr(request->rq_xprt);
    return NULL;
}

onc_text *onc_auth_1_svc(void *argument, struct svc_req *request) {
    static char buffer[512];
    static char *result = buffer;
    (void) argument;

    // Read the credential already decoded and authenticated by libtirpc.
    if (request->rq_cred.oa_flavor == AUTH_SYS) {
        struct authunix_parms *credentials = request->rq_clntcred;
        size_t used = (size_t) snprintf(buffer, sizeof(buffer), "sys:%u:%u:",
                (unsigned int) credentials->aup_uid, (unsigned int) credentials->aup_gid);
        for (u_int index = 0; index < credentials->aup_len && used < sizeof(buffer); index++) {
            int written = snprintf(buffer + used, sizeof(buffer) - used, "%s%u",
                    index == 0 ? "" : ",", (unsigned int) credentials->aup_gids[index]);
            if (written < 0) {
                break;
            }
            used += (size_t) written;
        }
    } else {
        snprintf(buffer, sizeof(buffer), "none");
    }
    return &result;
}

int *onc_delay_1_svc(int *argument, struct svc_req *request) {
    static int result;
    (void) request;
    result = *argument;
    if (result > 0 && result <= 10000) {
        usleep((useconds_t) result * 1000U);
    }
    return &result;
}

int *onc_increment_1_svc(int *argument, struct svc_req *request) {
    (void) request;
    counter += *argument;
    return &counter;
}

int *onc_count_1_svc(void *argument, struct svc_req *request) {
    (void) argument;
    (void) request;
    return &counter;
}

void *onc_reset_1_svc(void *argument, struct svc_req *request) {
    static char result;
    (void) argument;
    (void) request;
    counter = 0;
    return &result;
}

onc_bytes *onc_bytes_1_svc(onc_bytes *argument, struct svc_req *request) {
    (void) request;
    return argument;
}

static int parse_number(const char *text, int minimum, int maximum, int *value) {
    char *end = NULL;
    errno = 0;
    long parsed = strtol(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' || parsed < minimum || parsed > maximum) {
        fprintf(stderr, "Invalid numeric argument: %s\n", text);
        return -1;
    }
    *value = (int) parsed;
    return 0;
}

static int parse_options(int argc, char **argv, struct peer_options *options) {
    *options = (struct peer_options) {
        .command = "client", .transport = "tcp", .host = "127.0.0.1",
        .scenario = "echo", .value = NULL, .auth = "none", .port = 0
    };
    for (int index = 1; index < argc; index++) {
        const char *argument = argv[index];
        const char *equals = strchr(argument, '=');
        if (strncmp(argument, "--", 2) != 0 || equals == NULL) {
            fprintf(stderr, "Expected --name=value: %s\n", argument);
            return -1;
        }
        size_t length = (size_t) (equals - argument);
        const char *value = equals + 1;
        if (length == 9 && strncmp(argument, "--command", length) == 0) {
            options->command = value;
        } else if (length == 11 && strncmp(argument, "--transport", length) == 0) {
            options->transport = value;
        } else if (length == 6 && strncmp(argument, "--host", length) == 0) {
            options->host = value;
        } else if (length == 10 && strncmp(argument, "--scenario", length) == 0) {
            options->scenario = value;
        } else if (length == 7 && strncmp(argument, "--value", length) == 0) {
            options->value = value;
        } else if (length == 6 && strncmp(argument, "--auth", length) == 0) {
            options->auth = value;
        } else if (length == 6 && strncmp(argument, "--port", length) == 0) {
            int port;
            if (parse_number(value, 0, 65535, &port) != 0) {
                return -1;
            }
            options->port = (unsigned short) port;
        } else {
            fprintf(stderr, "Unknown option: %s\n", argument);
            return -1;
        }
    }
    if (strcmp(options->command, "server") != 0 && strcmp(options->command, "client") != 0) {
        fprintf(stderr, "Unsupported command: %s\n", options->command);
        return -1;
    }
    if (strcmp(options->transport, "tcp") != 0 && strcmp(options->transport, "udp") != 0) {
        fprintf(stderr, "Unsupported transport: %s\n", options->transport);
        return -1;
    }
    if (strcmp(options->auth, "none") != 0 && strcmp(options->auth, "sys") != 0) {
        fprintf(stderr, "Unsupported auth: %s\n", options->auth);
        return -1;
    }
    if (strcmp(options->command, "client") == 0 && options->port == 0) {
        fprintf(stderr, "Client requires a nonzero --port\n");
        return -1;
    }
    if (options->value == NULL) {
        if (strcmp(options->scenario, "bytes") == 0) {
            options->value = "257";
        } else if (strcmp(options->scenario, "delay") == 0) {
            options->value = "250";
        } else if (strcmp(options->scenario, "increment") == 0) {
            options->value = "1";
        } else {
            options->value = "native-echo";
        }
    }
    return 0;
}

static int socket_address(const struct peer_options *options, struct sockaddr_storage *address,
        socklen_t *address_size) {
    // Resolve container DNS names as well as explicit IPv4/IPv6 addresses.
    struct addrinfo hints = {0};
    hints.ai_family = strchr(options->host, ':') == NULL ? AF_INET : AF_INET6;
    hints.ai_socktype = strcmp(options->transport, "udp") == 0 ? SOCK_DGRAM : SOCK_STREAM;
    char port[6];
    snprintf(port, sizeof(port), "%u", (unsigned int) options->port);
    struct addrinfo *resolved = NULL;
    int status = getaddrinfo(options->host, port, &hints, &resolved);
    if (status != 0) {
        fprintf(stderr, "Cannot resolve --host %s: %s\n", options->host, gai_strerror(status));
        return -1;
    }
    memset(address, 0, sizeof(*address));
    memcpy(address, resolved->ai_addr, resolved->ai_addrlen);
    *address_size = (socklen_t) resolved->ai_addrlen;
    freeaddrinfo(resolved);
    return 0;
}

static int run_server(const struct peer_options *options) {
    struct sockaddr_storage address;
    socklen_t address_size;
    if (socket_address(options, &address, &address_size) != 0) {
        return 2;
    }

    // Bind explicitly; protocol zero below deliberately avoids the local rpcbind service.
    int datagram = strcmp(options->transport, "udp") == 0;
    int descriptor = socket(address.ss_family, datagram ? SOCK_DGRAM : SOCK_STREAM, 0);
    if (descriptor < 0) {
        perror("socket");
        return 2;
    }
    int reuse = 1;
    setsockopt(descriptor, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    if (bind(descriptor, (struct sockaddr *) &address, address_size) != 0
            || (!datagram && listen(descriptor, SOMAXCONN) != 0)) {
        perror("bind/listen");
        close(descriptor);
        return 2;
    }
    address_size = sizeof(address);
    if (getsockname(descriptor, (struct sockaddr *) &address, &address_size) != 0) {
        perror("getsockname");
        close(descriptor);
        return 2;
    }
    SVCXPRT *transport = datagram
            ? svc_dg_create(descriptor, 65536, 65536)
            : svc_vc_create(descriptor, 65536, 65536);
    if (transport == NULL) {
        fprintf(stderr, "Cannot create libtirpc server transport\n");
        close(descriptor);
        return 2;
    }
    if (!svc_register(transport, ONC_INTEROP, ONC_INTEROP_VERSION, onc_interop_1, 0)) {
        fprintf(stderr, "Cannot register generated dispatcher\n");
        svc_destroy(transport);
        return 2;
    }
    unsigned short port = address.ss_family == AF_INET
            ? ((struct sockaddr_in *) &address)->sin_port : ((struct sockaddr_in6 *) &address)->sin6_port;
    printf("READY %u\n", (unsigned int) ntohs(port));
    fflush(stdout);

    // Keep libtirpc dispatch single-threaded; stdin EOF is the bounded, graceful stop signal.
    int result = 0;
    for (;;) {
        struct pollfd input = {.fd = STDIN_FILENO, .events = POLLIN};
        int input_ready = poll(&input, 1, 0);
        if (input_ready < 0 && errno != EINTR) {
            perror("poll stdin");
            result = 2;
            break;
        }
        if (input_ready > 0 && (input.revents & (POLLIN | POLLHUP | POLLERR))) {
            char buffer[64];
            ssize_t length = read(STDIN_FILENO, buffer, sizeof(buffer));
            if (length == 0) {
                break;
            }
            if (length < 0 && errno != EINTR) {
                perror("read stdin");
                result = 2;
                break;
            }
        }
        int ready = poll(svc_pollfd, (nfds_t) svc_max_pollfd, 250);
        if (ready > 0) {
            svc_getreq_poll(svc_pollfd, ready);
        } else if (ready < 0 && errno != EINTR) {
            perror("poll libtirpc");
            result = 2;
            break;
        }
    }
    svc_destroy(transport);
    return result;
}

static void unsupported_nextverf(AUTH *authentication) {
    (void) authentication;
}

static int unsupported_marshal(AUTH *authentication, XDR *stream) {
    return xdr_opaque_auth(stream, &authentication->ah_cred)
            && xdr_opaque_auth(stream, &authentication->ah_verf);
}

static int unsupported_validate(AUTH *authentication, struct opaque_auth *verifier) {
    (void) authentication;
    (void) verifier;
    return TRUE;
}

static int unsupported_refresh(AUTH *authentication, void *message) {
    (void) authentication;
    (void) message;
    return FALSE;
}

static int unsupported_transfer(AUTH *authentication, XDR *stream, xdrproc_t procedure, caddr_t value) {
    (void) authentication;
    return procedure(stream, value);
}

static void unsupported_destroy(AUTH *authentication) {
    free(authentication);
}

static AUTH *create_unsupported_auth(void) {
    static struct auth_ops operations = {
        .ah_nextverf = unsupported_nextverf,
        .ah_marshal = unsupported_marshal,
        .ah_validate = unsupported_validate,
        .ah_refresh = unsupported_refresh,
        .ah_destroy = unsupported_destroy,
        .ah_wrap = unsupported_transfer,
        .ah_unwrap = unsupported_transfer
    };
    AUTH *authentication = calloc(1, sizeof(*authentication));
    if (authentication != NULL) {
        authentication->ah_cred.oa_flavor = 777;
        authentication->ah_ops = &operations;
    }
    return authentication;
}

static int connect_tcp(struct sockaddr *address, socklen_t address_size) {
    int descriptor = socket(address->sa_family, SOCK_STREAM, 0);
    if (descriptor < 0) {
        return -1;
    }
    int flags = fcntl(descriptor, F_GETFL, 0);
    if (flags < 0 || fcntl(descriptor, F_SETFL, flags | O_NONBLOCK) < 0) {
        close(descriptor);
        return -1;
    }
    int connected = connect(descriptor, address, address_size);
    if (connected < 0 && errno == EINPROGRESS) {
        struct pollfd connection = {.fd = descriptor, .events = POLLOUT};
        connected = poll(&connection, 1, 3000);
        if (connected > 0) {
            int error = 0;
            socklen_t length = sizeof(error);
            connected = getsockopt(descriptor, SOL_SOCKET, SO_ERROR, &error, &length);
            if (connected == 0 && error != 0) {
                errno = error;
                connected = -1;
            }
        } else {
            errno = connected == 0 ? ETIMEDOUT : errno;
            connected = -1;
        }
    }
    if (connected < 0 || fcntl(descriptor, F_SETFL, flags) < 0) {
        close(descriptor);
        return -1;
    }
    return descriptor;
}

static const char *rpc_status_name(enum clnt_stat status) {
    switch (status) {
        case RPC_SUCCESS: return "RPC_SUCCESS";
        case RPC_CANTENCODEARGS: return "RPC_CANTENCODEARGS";
        case RPC_CANTDECODERES: return "RPC_CANTDECODERES";
        case RPC_CANTSEND: return "RPC_CANTSEND";
        case RPC_CANTRECV: return "RPC_CANTRECV";
        case RPC_TIMEDOUT: return "RPC_TIMEDOUT";
        case RPC_VERSMISMATCH: return "RPC_VERSMISMATCH";
        case RPC_AUTHERROR: return "RPC_AUTHERROR";
        case RPC_PROGUNAVAIL: return "RPC_PROGUNAVAIL";
        case RPC_PROGVERSMISMATCH: return "RPC_PROGVERSMISMATCH";
        case RPC_PROCUNAVAIL: return "RPC_PROCUNAVAIL";
        case RPC_CANTDECODEARGS: return "RPC_CANTDECODEARGS";
        case RPC_SYSTEMERROR: return "RPC_SYSTEMERROR";
        default: return "RPC_OTHER_ERROR";
    }
}

static int checked_call(CLIENT *client, rpcproc_t procedure, xdrproc_t argument_type, void *argument,
        xdrproc_t result_type, void *result, enum clnt_stat expected, const char *scenario) {
    struct timeval timeout = {.tv_sec = 3, .tv_usec = 0};
    enum clnt_stat status = clnt_call(client, procedure, argument_type, argument, result_type, result, timeout);
    if (status != expected) {
        fprintf(stderr, "Unexpected RPC status for %s: expected %s, got %s (%s)\n", scenario,
                rpc_status_name(expected), rpc_status_name(status), clnt_sperror(client, "peer"));
        return -1;
    }
    if (status != RPC_SUCCESS) {
        printf("EXPECTED_ERROR %s=%s\n", scenario, rpc_status_name(status));
    }
    return 0;
}

static void create_sample(onc_sample *sample) {
    static char bytes[] = {0, 1, 127, (char) 128, (char) 255, 0};
    static int numbers[] = {-2147483647, 7, 2147483647};
    static onc_optional tail = {.value = -9, .next = NULL};
    static onc_optional head = {.value = 42, .next = &tail};
    memset(sample, 0, sizeof(*sample));
    sample->signed_value = -123456789;
    sample->unsigned_value = UINT32_C(0xfedcba98);
    sample->signed_hyper = -INT64_C(81985529216486895);
    sample->unsigned_hyper = UINT64_C(0xfedcba9876543210);
    sample->float_value = 1.25f;
    sample->double_value = -3.141592653589793;
    sample->bool_value = TRUE;
    sample->text = "native-sample";
    memcpy(sample->fixed_opaque, bytes, sizeof(sample->fixed_opaque));
    sample->bytes.onc_bytes_len = sizeof(bytes);
    sample->bytes.onc_bytes_val = bytes;
    sample->fixed_numbers[0] = -1;
    sample->fixed_numbers[1] = 0;
    sample->fixed_numbers[2] = 42;
    sample->numbers.onc_numbers_len = sizeof(numbers) / sizeof(numbers[0]);
    sample->numbers.onc_numbers_val = numbers;
    sample->colour = ONC_BLUE;
    sample->choice.tag = 2;
    sample->choice.onc_choice_u.text = "choice-sample";
    sample->optional = &head;
}

static int sample_equal(const onc_sample *left, const onc_sample *right) {
    if (left->signed_value != right->signed_value || left->unsigned_value != right->unsigned_value
            || left->signed_hyper != right->signed_hyper || left->unsigned_hyper != right->unsigned_hyper
            || left->float_value != right->float_value || left->double_value != right->double_value
            || left->bool_value != right->bool_value || strcmp(left->text, right->text) != 0
            || memcmp(left->fixed_opaque, right->fixed_opaque, sizeof(left->fixed_opaque)) != 0
            || left->bytes.onc_bytes_len != right->bytes.onc_bytes_len
            || memcmp(left->bytes.onc_bytes_val, right->bytes.onc_bytes_val, left->bytes.onc_bytes_len) != 0
            || memcmp(left->fixed_numbers, right->fixed_numbers, sizeof(left->fixed_numbers)) != 0
            || left->numbers.onc_numbers_len != right->numbers.onc_numbers_len
            || memcmp(left->numbers.onc_numbers_val, right->numbers.onc_numbers_val,
                    left->numbers.onc_numbers_len * sizeof(int)) != 0
            || left->colour != right->colour || right->choice.tag != 2
            || strcmp(left->choice.onc_choice_u.text, right->choice.onc_choice_u.text) != 0) {
        return 0;
    }
    return right->optional != NULL && right->optional->value == 42 && right->optional->next != NULL
            && right->optional->next->value == -9 && right->optional->next->next == NULL;
}

#include "oncrpc_types.h"

static int run_scenario(CLIENT *client, const struct peer_options *options) {
    const char *scenario = options->scenario;
    int result = 0;
    if (strcmp(scenario, "types") == 0) {
        return run_type_scenario(client, options);
    }

    // Error cases use the official client status mapping, without decoding custom wire replies.
    enum clnt_stat expected = RPC_SUCCESS;
    rpcproc_t procedure = NULLPROC;
    if (strcmp(scenario, "wrong-program") == 0) {
        expected = RPC_PROGUNAVAIL;
    } else if (strcmp(scenario, "wrong-version") == 0) {
        expected = RPC_PROGVERSMISMATCH;
    } else if (strcmp(scenario, "wrong-procedure") == 0) {
        expected = RPC_PROCUNAVAIL;
        procedure = 999;
    } else if (strcmp(scenario, "unsupported-auth") == 0) {
        expected = RPC_AUTHERROR;
        procedure = ONC_ECHO;
    } else if (strcmp(scenario, "weak-auth") == 0) {
        expected = RPC_AUTHERROR;
        procedure = ONC_ECHO;
    } else if (strcmp(scenario, "crash") == 0) {
        expected = RPC_SYSTEMERROR;
        procedure = ONC_CRASH;
    } else if (strcmp(scenario, "bad-arguments") == 0) {
        expected = RPC_CANTDECODEARGS;
        procedure = ONC_ECHO;
    }
    if (expected != RPC_SUCCESS) {
        onc_text text = (char *) options->value;
        int authenticated_echo = strcmp(scenario, "unsupported-auth") == 0 || strcmp(scenario, "weak-auth") == 0;
        xdrproc_t argument_type = authenticated_echo
                ? (xdrproc_t) xdr_onc_text : (xdrproc_t) xdr_void;
        u_int invalid_length = UINT_MAX;
        void *argument = &text;
        if (strcmp(scenario, "bad-arguments") == 0) {
            // An impossible string length is rejected even when a datagram receive buffer is reused.
            argument_type = (xdrproc_t) xdr_u_int;
            argument = &invalid_length;
        }
        return checked_call(client, procedure, argument_type, argument, (xdrproc_t) xdr_void, NULL,
                expected, scenario);
    }

    if (strcmp(scenario, "echo") == 0 || strcmp(scenario, "auth") == 0) {
        onc_text argument = (char *) options->value;
        onc_text response = NULL;
        int auth = strcmp(scenario, "auth") == 0;
        result = checked_call(client, auth ? ONC_AUTH : ONC_ECHO,
                auth ? (xdrproc_t) xdr_void : (xdrproc_t) xdr_onc_text, &argument,
                (xdrproc_t) xdr_onc_text, &response, RPC_SUCCESS, scenario);
        const char *expected_text = auth
                ? (strcmp(options->auth, "sys") == 0 ? "sys:1001:1002:10,20" : "none")
                : options->value;
        if (result == 0 && (response == NULL || strcmp(response, expected_text) != 0)) {
            fprintf(stderr, "Unexpected %s response: %s\n", scenario, response == NULL ? "(null)" : response);
            result = -1;
        }
        if (result == 0) {
            printf("RESULT %s=%s\n", scenario, response);
        }
        xdr_free((xdrproc_t) xdr_onc_text, (char *) &response);
    } else if (strcmp(scenario, "add") == 0) {
        onc_pair argument = {.left = 19, .right = 23};
        int response = 0;
        result = checked_call(client, ONC_ADD, (xdrproc_t) xdr_onc_pair, &argument,
                (xdrproc_t) xdr_int, &response, RPC_SUCCESS, scenario);
        if (result == 0 && response != 1923) {
            fprintf(stderr, "Noncommutative add returned %d instead of 1923\n", response);
            result = -1;
        }
        if (result == 0) {
            printf("RESULT add=%d\n", response);
        }
    } else if (strcmp(scenario, "mirror") == 0) {
        onc_sample argument;
        onc_sample response = {0};
        create_sample(&argument);
        result = checked_call(client, ONC_MIRROR, (xdrproc_t) xdr_onc_sample, &argument,
                (xdrproc_t) xdr_onc_sample, &response, RPC_SUCCESS, scenario);
        if (result == 0 && !sample_equal(&argument, &response)) {
            fprintf(stderr, "Typed XDR mirror fields differ\n");
            result = -1;
        }
        if (result == 0) {
            printf("RESULT mirror=ok\n");
        }
        xdr_free((xdrproc_t) xdr_onc_sample, (char *) &response);
    } else if (strcmp(scenario, "bytes") == 0) {
        int length = 0;
        if (parse_number(options->value, 0, 60000, &length) != 0) {
            return -1;
        }
        onc_bytes argument = {.onc_bytes_len = (u_int) length, .onc_bytes_val = malloc((size_t) length + 1)};
        onc_bytes response = {0};
        if (argument.onc_bytes_val == NULL) {
            perror("malloc bytes");
            return -1;
        }
        for (int index = 0; index < length; index++) {
            argument.onc_bytes_val[index] = (char) (index * 31 + 7);
        }
        result = checked_call(client, ONC_BYTES, (xdrproc_t) xdr_onc_bytes, &argument,
                (xdrproc_t) xdr_onc_bytes, &response, RPC_SUCCESS, scenario);
        if (result == 0 && (response.onc_bytes_len != argument.onc_bytes_len
                || memcmp(argument.onc_bytes_val, response.onc_bytes_val, (size_t) length) != 0)) {
            fprintf(stderr, "Opaque XDR bytes differ\n");
            result = -1;
        }
        if (result == 0) {
            printf("RESULT bytes=%d\n", length);
        }
        free(argument.onc_bytes_val);
        xdr_free((xdrproc_t) xdr_onc_bytes, (char *) &response);
    } else if (strcmp(scenario, "delay") == 0 || strcmp(scenario, "increment") == 0
            || strcmp(scenario, "count") == 0) {
        int argument = 0;
        int response = 0;
        int count = strcmp(scenario, "count") == 0;
        if (!count && parse_number(options->value, 0, 10000, &argument) != 0) {
            return -1;
        }
        procedure = count ? ONC_COUNT : (strcmp(scenario, "delay") == 0 ? ONC_DELAY : ONC_INCREMENT);
        result = checked_call(client, procedure, count ? (xdrproc_t) xdr_void : (xdrproc_t) xdr_int,
                &argument, (xdrproc_t) xdr_int, &response, RPC_SUCCESS, scenario);
        if (result == 0) {
            printf("RESULT %s=%d\n", scenario, response);
        }
    } else if (strcmp(scenario, "ping") == 0 || strcmp(scenario, "null") == 0
            || strcmp(scenario, "reset") == 0) {
        procedure = strcmp(scenario, "ping") == 0 ? ONC_PING
                : (strcmp(scenario, "reset") == 0 ? ONC_RESET : NULLPROC);
        result = checked_call(client, procedure, (xdrproc_t) xdr_void, NULL,
                (xdrproc_t) xdr_void, NULL, RPC_SUCCESS, scenario);
        if (result == 0) {
            printf("RESULT %s=ok\n", scenario);
        }
    } else {
        fprintf(stderr, "Unknown scenario: %s\n", scenario);
        result = -1;
    }
    return result;
}

static int run_client(const struct peer_options *options) {
    struct sockaddr_storage address;
    socklen_t address_size;
    if (socket_address(options, &address, &address_size) != 0) {
        return 2;
    }
    rpcprog_t program = strcmp(options->scenario, "wrong-program") == 0 ? ONC_INTEROP + 1 : ONC_INTEROP;
    rpcvers_t version = strcmp(options->scenario, "wrong-version") == 0 ? ONC_INTEROP_VERSION + 1
            : ONC_INTEROP_VERSION;

    // All requests go through libtirpc CLIENT and the rpcgen-generated XDR routines.
    struct netbuf remote = {.maxlen = address_size, .len = address_size, .buf = &address};
    int descriptor;
    CLIENT *client;
    if (strcmp(options->transport, "udp") == 0) {
        descriptor = socket(address.ss_family, SOCK_DGRAM, 0);
        if (descriptor < 0) {
            perror("socket");
            return 2;
        }
        // Bind an ordinary ephemeral endpoint; no reserved port or rpcbind lookup is needed.
        struct sockaddr_storage local = {0};
        local.ss_family = address.ss_family;
        if (bind(descriptor, (struct sockaddr *) &local, address_size) != 0) {
            perror("bind client");
            close(descriptor);
            return 2;
        }
        client = clnt_dg_create(descriptor, &remote, program, version, 65536, 65536);
    } else {
        descriptor = connect_tcp((struct sockaddr *) &address, address_size);
        if (descriptor < 0) {
            perror("connect");
            return 2;
        }
        client = clnt_vc_create(descriptor, &remote, program, version, 65536, 65536);
    }
    if (client == NULL) {
        clnt_pcreateerror("Cannot create libtirpc client");
        close(descriptor);
        return 2;
    }
    clnt_control(client, CLSET_FD_CLOSE, NULL);
    struct timeval timeout = {.tv_sec = 3, .tv_usec = 0};
    clnt_control(client, CLSET_TIMEOUT, (char *) &timeout);
    if (strcmp(options->transport, "udp") == 0) {
        struct timeval retry = {.tv_sec = 0, .tv_usec = 300000};
        clnt_control(client, CLSET_RETRY_TIMEOUT, (char *) &retry);
    }

    AUTH *authentication = NULL;
    if (strcmp(options->scenario, "unsupported-auth") == 0) {
        authentication = create_unsupported_auth();
    } else if (strcmp(options->auth, "sys") == 0) {
        gid_t groups[] = {10, 20};
        authentication = authunix_create("native-peer", 1001, 1002, 2, groups);
    }
    if ((strcmp(options->scenario, "unsupported-auth") == 0 || strcmp(options->auth, "sys") == 0)
            && authentication == NULL) {
        fprintf(stderr, "Cannot create client credentials\n");
        clnt_destroy(client);
        return 2;
    }
    if (authentication != NULL) {
        client->cl_auth = authentication;
    }

    int result = run_scenario(client, options);
    if (authentication != NULL) {
        auth_destroy(authentication);
        client->cl_auth = authnone_create();
    }
    clnt_destroy(client);
    fflush(stdout);
    return result == 0 ? 0 : 3;
}

int main(int argc, char **argv) {
    struct peer_options options;
    signal(SIGPIPE, SIG_IGN);
    if (parse_options(argc, argv, &options) != 0) {
        return 2;
    }
    return strcmp(options.command, "server") == 0 ? run_server(&options) : run_client(&options);
}
