/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */

/* Independent rpcbind fixture on the private test network. libtirpc owns all RPC/XDR encoding. */
#include <rpc/rpc.h>
#include <rpc/pmap_prot.h>
#include <rpc/rpcb_prot.h>
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define TEST_PROGRAM 536870913U
#define MUTABLE_PROGRAM 536870914U
#define MAX_MAPPINGS 64
#define MAX_NETID 16
#define MAX_ADDRESS 128
#define MAX_OWNER 64

struct mapping {
    int active;
    rpcprog_t program;
    rpcvers_t version;
    char netid[MAX_NETID];
    char address[MAX_ADDRESS];
    char owner[MAX_OWNER];
    u_long port;
};

static struct mapping mappings[MAX_MAPPINGS];
static const char *server_netid;

static void fail(const char *message) {
    perror(message);
    exit(1);
}

static bool_t xdr_empty(XDR *stream, void *value) {
    (void) stream;
    (void) value;
    return TRUE;
}

static u_long address_port(const char *address) {
    const char *low = strrchr(address, '.');
    if (low == NULL || low == address) {
        return 0;
    }
    const char *high = low - 1;
    while (high > address && *high != '.') {
        high--;
    }
    if (*high != '.') {
        return 0;
    }
    char *end;
    unsigned long high_byte = strtoul(high + 1, &end, 10);
    if (end != low || high_byte > 255) {
        return 0;
    }
    unsigned long low_byte = strtoul(low + 1, &end, 10);
    if (*end != '\0' || low_byte > 255) {
        return 0;
    }
    return high_byte * 256 + low_byte;
}

static struct mapping *find_mapping(rpcprog_t program, rpcvers_t version,
                                    const char *netid, int exact_version) {
    struct mapping *fallback = NULL;
    for (size_t index = 0; index < MAX_MAPPINGS; index++) {
        struct mapping *entry = &mappings[index];
        if (!entry->active || entry->program != program || strcmp(entry->netid, netid) != 0) {
            continue;
        }
        if (entry->version == version) {
            return entry;
        }
        fallback = entry;
    }
    return exact_version ? NULL : fallback;
}

static bool_t set_mapping(const rpcb *value) {
    if (value->r_netid == NULL || value->r_addr == NULL || value->r_owner == NULL
        || strlen(value->r_netid) >= MAX_NETID || strlen(value->r_addr) >= MAX_ADDRESS
        || strlen(value->r_owner) >= MAX_OWNER) {
        return FALSE;
    }
    if (strcmp(value->r_netid, "tcp") != 0 && strcmp(value->r_netid, "udp") != 0) {
        return FALSE;
    }
    u_long port = address_port(value->r_addr);
    if (port == 0) {
        return FALSE;
    }
    struct mapping *entry = find_mapping(value->r_prog, value->r_vers, value->r_netid, 1);
    if (entry != NULL) {
        return strcmp(entry->address, value->r_addr) == 0;
    }
    for (size_t index = 0; index < MAX_MAPPINGS; index++) {
        if (!mappings[index].active) {
            entry = &mappings[index];
            memset(entry, 0, sizeof(*entry));
            entry->active = 1;
            entry->program = value->r_prog;
            entry->version = value->r_vers;
            entry->port = port;
            strcpy(entry->netid, value->r_netid);
            strcpy(entry->address, value->r_addr);
            strcpy(entry->owner, value->r_owner);
            return TRUE;
        }
    }
    return FALSE;
}

static bool_t unset_mapping(rpcprog_t program, rpcvers_t version, const char *netid) {
    for (size_t index = 0; index < MAX_MAPPINGS; index++) {
        struct mapping *entry = &mappings[index];
        if (!entry->active || entry->program != program || entry->version != version) {
            continue;
        }
        if (netid == NULL || *netid == '\0' || strcmp(entry->netid, netid) == 0) {
            memset(entry, 0, sizeof(*entry));
        }
    }
    return TRUE;
}

static int trusted_caller(SVCXPRT *transport) {
    const struct netbuf *caller = svc_getrpccaller(transport);
    if (caller == NULL || caller->buf == NULL || caller->len < sizeof(struct sockaddr_in)) {
        return 0;
    }
    const struct sockaddr_in *address = caller->buf;
    if (address->sin_family != AF_INET) {
        return 0;
    }
    if (ntohl(address->sin_addr.s_addr) == INADDR_LOOPBACK) {
        return 1;
    }

    // Only the owning JUnit runner may mutate this isolated fixture over the bridge network.
    const char *runner = getenv("METEOR_TEST_RUNNER_HOST");
    if (runner == NULL) {
        return 0;
    }
    struct addrinfo hints = {.ai_family = AF_INET, .ai_socktype = SOCK_STREAM};
    struct addrinfo *resolved = NULL;
    if (getaddrinfo(runner, NULL, &hints, &resolved) != 0) {
        return 0;
    }
    int accepted = 0;
    for (struct addrinfo *entry = resolved; entry != NULL; entry = entry->ai_next) {
        const struct sockaddr_in *trusted = (const struct sockaddr_in *) entry->ai_addr;
        if (trusted->sin_addr.s_addr == address->sin_addr.s_addr) {
            accepted = 1;
            break;
        }
    }
    freeaddrinfo(resolved);
    return accepted;
}

static void send_result(SVCXPRT *transport, xdrproc_t encoder, void *result) {
    if (!svc_sendreply(transport, encoder, result)) {
        svcerr_systemerr(transport);
    }
}

static void dump_pmap(SVCXPRT *transport) {
    struct pmaplist entries[MAX_MAPPINGS];
    struct pmaplist *head = NULL;
    struct pmaplist **tail = &head;
    size_t count = 0;
    for (size_t index = 0; index < MAX_MAPPINGS; index++) {
        const struct mapping *entry = &mappings[index];
        if (!entry->active) {
            continue;
        }
        struct pmaplist *node = &entries[count++];
        memset(node, 0, sizeof(*node));
        node->pml_map.pm_prog = entry->program;
        node->pml_map.pm_vers = entry->version;
        node->pml_map.pm_prot = strcmp(entry->netid, "tcp") == 0 ? IPPROTO_TCP : IPPROTO_UDP;
        node->pml_map.pm_port = entry->port;
        *tail = node;
        tail = &node->pml_next;
    }
    send_result(transport, (xdrproc_t) xdr_pmaplist, &head);
}

static void dump_rpcb(SVCXPRT *transport) {
    rp__list entries[MAX_MAPPINGS];
    rpcblist_ptr head = NULL;
    rpcblist_ptr *tail = &head;
    size_t count = 0;
    for (size_t index = 0; index < MAX_MAPPINGS; index++) {
        struct mapping *entry = &mappings[index];
        if (!entry->active) {
            continue;
        }
        rp__list *node = &entries[count++];
        memset(node, 0, sizeof(*node));
        node->rpcb_map.r_prog = entry->program;
        node->rpcb_map.r_vers = entry->version;
        node->rpcb_map.r_netid = entry->netid;
        node->rpcb_map.r_addr = entry->address;
        node->rpcb_map.r_owner = entry->owner;
        *tail = node;
        tail = &node->rpcb_next;
    }
    send_result(transport, (xdrproc_t) xdr_rpcblist_ptr, &head);
}

static void dispatch_pmap(struct svc_req *request, SVCXPRT *transport) {
    if (request->rq_proc == PMAPPROC_DUMP) {
        dump_pmap(transport);
        return;
    }
    if (request->rq_proc < PMAPPROC_SET || request->rq_proc > PMAPPROC_GETPORT) {
        svcerr_noproc(transport);
        return;
    }

    struct pmap argument;
    memset(&argument, 0, sizeof(argument));
    if (!svc_getargs(transport, (xdrproc_t) xdr_pmap, &argument)) {
        svcerr_decode(transport);
        return;
    }
    const char *netid = argument.pm_prot == IPPROTO_TCP ? "tcp" : "udp";
    if (request->rq_proc == PMAPPROC_GETPORT) {
        struct mapping *entry = find_mapping(argument.pm_prog, argument.pm_vers, netid, 0);
        u_long port = entry == NULL ? 0 : entry->port;
        if (argument.pm_prot != IPPROTO_TCP && argument.pm_prot != IPPROTO_UDP) {
            port = 0;
        }
        send_result(transport, (xdrproc_t) xdr_u_long, &port);
    } else {
        bool_t result = FALSE;
        if (trusted_caller(transport)) {
            if (request->rq_proc == PMAPPROC_UNSET) {
                result = unset_mapping(argument.pm_prog, argument.pm_vers, NULL);
            } else if (argument.pm_port > 0 && argument.pm_port <= 65535
                       && (argument.pm_prot == IPPROTO_TCP || argument.pm_prot == IPPROTO_UDP)) {
                char address[MAX_ADDRESS];
                snprintf(address, sizeof(address), "127.0.0.1.%lu.%lu",
                         argument.pm_port / 256, argument.pm_port % 256);
                rpcb value = {argument.pm_prog, argument.pm_vers, (char *) netid,
                              address, "java-test"};
                result = set_mapping(&value);
            }
        }
        send_result(transport, (xdrproc_t) xdr_bool, &result);
    }
    if (!svc_freeargs(transport, (xdrproc_t) xdr_pmap, &argument)) {
        fail("free pmap arguments");
    }
}

static void dispatch_rpcb(struct svc_req *request, SVCXPRT *transport) {
    if (request->rq_proc == RPCBPROC_DUMP) {
        dump_rpcb(transport);
        return;
    }
    if (request->rq_proc == RPCBPROC_GETTIME) {
        u_long now = (u_long) time(NULL);
        send_result(transport, (xdrproc_t) xdr_u_long, &now);
        return;
    }
    int get_address = request->rq_proc == RPCBPROC_GETADDR;
    int get_exact = request->rq_vers == RPCBVERS4 && request->rq_proc == RPCBPROC_GETVERSADDR;
    if (!get_address && !get_exact && request->rq_proc != RPCBPROC_SET
        && request->rq_proc != RPCBPROC_UNSET) {
        svcerr_noproc(transport);
        return;
    }

    rpcb argument;
    memset(&argument, 0, sizeof(argument));
    if (!svc_getargs(transport, (xdrproc_t) xdr_rpcb, &argument)) {
        svcerr_decode(transport);
        return;
    }
    if (get_address || get_exact) {
        /* RFC 1833 lookup derives netid from the actual request transport. */
        struct mapping *entry = find_mapping(argument.r_prog, argument.r_vers, server_netid, get_exact);
        char *address = entry == NULL ? "" : entry->address;
        send_result(transport, (xdrproc_t) xdr_wrapstring, &address);
    } else {
        bool_t result = FALSE;
        if (trusted_caller(transport)) {
            if (request->rq_proc == RPCBPROC_SET) {
                result = set_mapping(&argument);
            } else {
                result = unset_mapping(argument.r_prog, argument.r_vers, argument.r_netid);
            }
        }
        send_result(transport, (xdrproc_t) xdr_bool, &result);
    }
    if (!svc_freeargs(transport, (xdrproc_t) xdr_rpcb, &argument)) {
        fail("free rpcb arguments");
    }
}

static void dispatch(struct svc_req *request, SVCXPRT *transport) {
    if (request->rq_proc == NULLPROC) {
        send_result(transport, (xdrproc_t) xdr_empty, NULL);
    } else if (request->rq_vers == PMAPVERS) {
        dispatch_pmap(request, transport);
    } else {
        dispatch_rpcb(request, transport);
    }
}

static void run_server(const char *transport_name, unsigned int port) {
    int tcp = strcmp(transport_name, "tcp") == 0;
    int socket_fd = socket(AF_INET, tcp ? SOCK_STREAM : SOCK_DGRAM, 0);
    if (socket_fd < 0) {
        fail("socket");
    }
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons((uint16_t) port);
    if (bind(socket_fd, (struct sockaddr *) &address, sizeof(address)) != 0) {
        fail("bind");
    }
    if (tcp && listen(socket_fd, 8) != 0) {
        fail("listen");
    }
    socklen_t address_size = sizeof(address);
    if (getsockname(socket_fd, (struct sockaddr *) &address, &address_size) != 0) {
        fail("getsockname");
    }
    SVCXPRT *service = tcp ? svctcp_create(socket_fd, 0, 0) : svcudp_create(socket_fd);
    if (service == NULL) {
        fail("create RPC service transport");
    }

    /* Protocol 0 registers only in this process; never contact system rpcbind. */
    if (!svc_register(service, PMAPPROG, PMAPVERS, dispatch, 0)
        || !svc_register(service, RPCBPROG, RPCBVERS, dispatch, 0)
        || !svc_register(service, RPCBPROG, RPCBVERS4, dispatch, 0)) {
        fail("register local RPC dispatch");
    }
    server_netid = transport_name;
    rpcb tcp_seed = {TEST_PROGRAM, 1, "tcp", "127.0.0.1.31.187", "java-test"};
    rpcb udp_seed = {TEST_PROGRAM, 1, "udp", "127.0.0.1.31.188", "java-test"};
    if (!set_mapping(&tcp_seed) || !set_mapping(&udp_seed)) {
        fail("seed fixture mappings");
    }
    printf("READY %u\n", ntohs(address.sin_port));
    fflush(stdout);

    /* stdin EOF is the fixture owner's shutdown signal. */
    for (;;) {
        fd_set ready = svc_fdset;
        FD_SET(STDIN_FILENO, &ready);
        int count = select(svc_maxfd + 1, &ready, NULL, NULL, NULL);
        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            fail("select");
        }
        if (FD_ISSET(STDIN_FILENO, &ready)) {
            char input[64];
            if (read(STDIN_FILENO, input, sizeof(input)) <= 0) {
                break;
            }
            FD_CLR(STDIN_FILENO, &ready);
        }
        svc_getreqset(&ready);
    }
    svc_destroy(service);
}

static enum clnt_stat invoke(CLIENT *client, rpcproc_t procedure, xdrproc_t input,
                             void *argument, xdrproc_t output, void *result) {
    struct timeval timeout = {3, 0};
    return clnt_call(client, procedure, input, argument, output, result, timeout);
}

static void require_call(CLIENT *client, rpcproc_t procedure, xdrproc_t input,
                         void *argument, xdrproc_t output, void *result) {
    if (invoke(client, procedure, input, argument, output, result) != RPC_SUCCESS) {
        clnt_perror(client, "rpcbind peer call");
        exit(2);
    }
}

static void require_result(int valid, const char *message) {
    if (!valid) {
        fprintf(stderr, "%s\n", message);
        exit(3);
    }
}

static void client_lookup(CLIENT *client, unsigned int version, const char *transport_name,
                           int missing, int missing_version) {
    rpcprog_t program = missing ? TEST_PROGRAM + 100 : TEST_PROGRAM;
    rpcvers_t target_version = missing_version ? 999 : 1;
    u_long expected_port = strcmp(transport_name, "tcp") == 0 ? 8123 : 8124;
    if (version == PMAPVERS) {
        struct pmap argument = {program, target_version,
                                strcmp(transport_name, "tcp") == 0 ? IPPROTO_TCP : IPPROTO_UDP, 0};
        u_long result = 0;
        require_call(client, PMAPPROC_GETPORT, (xdrproc_t) xdr_pmap, &argument,
                     (xdrproc_t) xdr_u_long, &result);
        require_result(result == (missing ? 0 : expected_port), "incorrect GETPORT result");
        printf("RESULT %lu\n", result);
        return;
    }
    /* Deliberately wrong argument netid: lookup must use the transport instead. */
    rpcb argument = {program, target_version, "ignored-netid", "", ""};
    char *result = NULL;
    rpcproc_t procedure = version == RPCBVERS4 ? RPCBPROC_GETVERSADDR : RPCBPROC_GETADDR;
    require_call(client, procedure, (xdrproc_t) xdr_rpcb, &argument,
                 (xdrproc_t) xdr_wrapstring, &result);
    int absent = missing || (missing_version && version == RPCBVERS4);
    const char *expected = strcmp(transport_name, "tcp") == 0
                           ? "127.0.0.1.31.187" : "127.0.0.1.31.188";
    require_result(strcmp(result, absent ? "" : expected) == 0, "incorrect address lookup result");
    printf("RESULT %s\n", result);
    xdr_free((xdrproc_t) xdr_wrapstring, (char *) &result);
}

static void client_dump(CLIENT *client, unsigned int version) {
    unsigned int count = 0;
    if (version == PMAPVERS) {
        struct pmaplist *result = NULL;
        require_call(client, PMAPPROC_DUMP, (xdrproc_t) xdr_empty, NULL,
                     (xdrproc_t) xdr_pmaplist, &result);
        for (struct pmaplist *entry = result; entry != NULL; entry = entry->pml_next) {
            if (entry->pml_map.pm_prog == TEST_PROGRAM) {
                count++;
            }
        }
        xdr_free((xdrproc_t) xdr_pmaplist, (char *) &result);
    } else {
        rpcblist_ptr result = NULL;
        require_call(client, RPCBPROC_DUMP, (xdrproc_t) xdr_empty, NULL,
                     (xdrproc_t) xdr_rpcblist_ptr, &result);
        for (rpcblist_ptr entry = result; entry != NULL; entry = entry->rpcb_next) {
            if (entry->rpcb_map.r_prog == TEST_PROGRAM) {
                require_result(strcmp(entry->rpcb_map.r_owner, "java-test") == 0, "incorrect owner");
                count++;
            }
        }
        xdr_free((xdrproc_t) xdr_rpcblist_ptr, (char *) &result);
    }
    require_result(count == 2, "fixture dump must contain exactly two seed mappings");
    printf("RESULT dump:%u\n", count);
}

static void client_set_unset(CLIENT *client, unsigned int version, const char *transport_name) {
    bool_t result = FALSE;
    if (version == PMAPVERS) {
        struct pmap value = {MUTABLE_PROGRAM, 1,
                             strcmp(transport_name, "tcp") == 0 ? IPPROTO_TCP : IPPROTO_UDP, 8125};
        require_call(client, PMAPPROC_SET, (xdrproc_t) xdr_pmap, &value,
                     (xdrproc_t) xdr_bool, &result);
        require_result(result, "SET rejected local mapping");
        require_call(client, PMAPPROC_UNSET, (xdrproc_t) xdr_pmap, &value,
                     (xdrproc_t) xdr_bool, &result);
    } else {
        rpcb value = {MUTABLE_PROGRAM, 1, (char *) transport_name, "127.0.0.1.31.189", "java-test"};
        require_call(client, RPCBPROC_SET, (xdrproc_t) xdr_rpcb, &value,
                     (xdrproc_t) xdr_bool, &result);
        require_result(result, "SET rejected local mapping");
        require_call(client, RPCBPROC_UNSET, (xdrproc_t) xdr_rpcb, &value,
                     (xdrproc_t) xdr_bool, &result);
    }
    require_result(result, "UNSET rejected local mapping");
    printf("RESULT set-unset\n");
}

static void run_client(const char *transport_name, unsigned int port, unsigned int version,
                        const char *scenario) {
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    const char *runner = getenv("METEOR_TEST_RUNNER_HOST");
    if (runner == NULL) {
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    } else {
        struct addrinfo hints = {.ai_family = AF_INET, .ai_socktype = SOCK_STREAM};
        struct addrinfo *resolved = NULL;
        int status = getaddrinfo(runner, NULL, &hints, &resolved);
        if (status != 0) {
            fprintf(stderr, "Cannot resolve rpcbind server %s: %s\n", runner, gai_strerror(status));
            exit(2);
        }
        address.sin_addr = ((struct sockaddr_in *) resolved->ai_addr)->sin_addr;
        freeaddrinfo(resolved);
    }
    address.sin_port = htons((uint16_t) port);
    int socket_fd = RPC_ANYSOCK;
    CLIENT *client;
    if (strcmp(transport_name, "tcp") == 0) {
        client = clnttcp_create(&address, RPCBPROG, version, &socket_fd, 0, 0);
    } else {
        struct timeval retry = {0, 200000};
        client = clntudp_create(&address, RPCBPROG, version, retry, &socket_fd);
    }
    if (client == NULL) {
        clnt_pcreateerror("connect rpcbind fixture");
        exit(2);
    }

    if (strcmp(scenario, "lookup") == 0 || strcmp(scenario, "missing") == 0
        || strcmp(scenario, "missing-version") == 0) {
        client_lookup(client, version, transport_name, strcmp(scenario, "missing") == 0,
                      strcmp(scenario, "missing-version") == 0);
    } else if (strcmp(scenario, "dump") == 0) {
        client_dump(client, version);
    } else if (strcmp(scenario, "set-unset") == 0) {
        client_set_unset(client, version, transport_name);
    } else if (strcmp(scenario, "time") == 0) {
        require_result(version != PMAPVERS, "GETTIME requires rpcbind v3 or v4");
        u_long result = 0;
        require_call(client, RPCBPROC_GETTIME, (xdrproc_t) xdr_empty, NULL,
                     (xdrproc_t) xdr_u_long, &result);
        require_result(result > 1000000000UL, "incorrect GETTIME value");
        printf("RESULT time:%lu\n", result);
    } else if (strcmp(scenario, "unknown") == 0) {
        enum clnt_stat status = invoke(client, 999, (xdrproc_t) xdr_empty, NULL,
                                       (xdrproc_t) xdr_empty, NULL);
        require_result(status == RPC_PROCUNAVAIL, "unknown procedure must return PROC_UNAVAIL");
        printf("RESULT unknown-procedure\n");
    } else if (strcmp(scenario, "null") == 0) {
        require_call(client, NULLPROC, (xdrproc_t) xdr_empty, NULL, (xdrproc_t) xdr_empty, NULL);
        printf("RESULT null\n");
    } else {
        fprintf(stderr, "Unknown scenario: %s\n", scenario);
        exit(2);
    }
    clnt_destroy(client);
}

int main(int argc, char **argv) {
    const char *command = "server";
    const char *transport_name = "tcp";
    const char *scenario = "lookup";
    unsigned int port = 0;
    unsigned int version = RPCBVERS4;
    for (int index = 1; index < argc; index++) {
        const char *value = strchr(argv[index], '=');
        if (value == NULL) {
            fprintf(stderr, "Expected --name=value: %s\n", argv[index]);
            return 2;
        }
        value++;
        if (strncmp(argv[index], "--command=", 10) == 0) {
            command = value;
        } else if (strncmp(argv[index], "--transport=", 12) == 0) {
            transport_name = value;
        } else if (strncmp(argv[index], "--scenario=", 11) == 0) {
            scenario = value;
        } else if (strncmp(argv[index], "--port=", 7) == 0) {
            char *end;
            unsigned long parsed = strtoul(value, &end, 10);
            require_result(*end == '\0' && parsed <= 65535, "port must be between 0 and 65535");
            port = (unsigned int) parsed;
        } else if (strncmp(argv[index], "--version=", 10) == 0) {
            char *end;
            unsigned long parsed = strtoul(value, &end, 10);
            require_result(*end == '\0' && parsed >= 2 && parsed <= 4, "rpcbind version must be 2, 3 or 4");
            version = (unsigned int) parsed;
        } else {
            fprintf(stderr, "Unknown option: %s\n", argv[index]);
            return 2;
        }
    }
    require_result(strcmp(transport_name, "tcp") == 0 || strcmp(transport_name, "udp") == 0,
                   "transport must be tcp or udp");
    if (strcmp(command, "server") == 0) {
        run_server(transport_name, port);
    } else if (strcmp(command, "client") == 0 && port != 0) {
        run_client(transport_name, port, version, scenario);
    } else {
        fprintf(stderr, "command must be server or client; client requires a nonzero port\n");
        return 2;
    }
    return 0;
}
