/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

#include "order.h"
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

extern "C" void order_program_1(struct svc_req *, SVCXPRT *);

namespace {

// Business state uses ordinary C++ values; rpcgen only supplies the wire contract.
struct Order {
    int id;
    std::string description;
    std::vector<std::uint8_t> attachment;
};

std::map<int, Order> orders;

Order fromWire(const OrderValue &value) {
    Order order{value.id, "", {}};
    if (value.description != nullptr && *value.description != nullptr) {
        order.description = *value.description;
    }

    if (value.attachment != nullptr && value.attachment->OrderAttachment_len > 0) {
        const auto *bytes = reinterpret_cast<const std::uint8_t *>(value.attachment->OrderAttachment_val);
        order.attachment.assign(bytes, bytes + value.attachment->OrderAttachment_len);
    }
    return order;
}

OrderValue *toWire(const Order &order) {
    // svc_run dispatches serially and sends this result before the next request.
    static Order snapshot;
    static OrderDescription description;
    static OrderAttachment attachment;
    static OrderValue result;
    snapshot = order;
    description = const_cast<char *>(snapshot.description.c_str());
    attachment.OrderAttachment_len = static_cast<u_int>(snapshot.attachment.size());
    attachment.OrderAttachment_val = reinterpret_cast<char *>(snapshot.attachment.data());
    result.id = snapshot.id;
    result.description = &description;
    result.attachment = &attachment;
    return &result;
}

} // namespace

extern "C" OrderValue *order_save_1_svc(OrderValue *value, struct svc_req *request) {
    try {
        const auto order = fromWire(*value);
        orders[order.id] = order;
        return toWire(order);
    } catch (const std::exception &) {
        svcerr_systemerr(request->rq_xprt);
        return nullptr;
    }
}

extern "C" OrderValue *order_find_1_svc(int *id, struct svc_req *request) {
    const auto found = orders.find(*id);
    if (found == orders.end()) {
        svcerr_systemerr(request->rq_xprt);
        return nullptr;
    }

    try {
        return toWire(found->second);
    } catch (const std::exception &) {
        svcerr_systemerr(request->rq_xprt);
        return nullptr;
    }
}

int main() {
    try {
        // Bind the fixed local TCP port without rpcbind.
        sockaddr_in address{};
        address.sin_family = AF_INET;
        address.sin_port = htons(8080);
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

        const int socket = ::socket(AF_INET, SOCK_STREAM, 0);
        if (socket < 0) {
            throw std::runtime_error("cannot create TCP socket");
        }

        const int reuse = 1;
        setsockopt(socket, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
        if (bind(socket, reinterpret_cast<sockaddr *>(&address), sizeof(address)) != 0
            || listen(socket, SOMAXCONN) != 0) {
            const std::string error = std::strerror(errno);
            close(socket);
            throw std::runtime_error("cannot listen: " + error);
        }

        // The transport owns the socket after creation; protocol 0 skips rpcbind.
        SVCXPRT *transport = svc_vc_create(socket, 0, 0);
        if (transport == nullptr) {
            close(socket);
            throw std::runtime_error("cannot create ONC RPC transport");
        }

        if (!svc_register(transport, ORDER_PROGRAM, ORDER_VERSION, order_program_1, 0)) {
            svc_destroy(transport);
            throw std::runtime_error("cannot register the order service");
        }

        std::cout << "Order provider is ready: oncrpc://127.0.0.1:8080" << std::endl;
        svc_run();
        svc_destroy(transport);
        throw std::runtime_error("ONC RPC event loop stopped");
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
