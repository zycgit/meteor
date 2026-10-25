/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

#include "order.h"
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct Order {
    int id;
    std::string description;
    std::vector<std::uint8_t> attachment;
};

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

std::string jsonString(const std::string &value) {
    const char digits[] = "0123456789abcdef";
    std::string result = "\"";
    for (const unsigned char character : value) {
        if (character == '"' || character == '\\') {
            result += '\\';
            result += character;
        } else if (character < 0x20) {
            result += "\\u00";
            result += digits[character >> 4];
            result += digits[character & 0x0f];
        } else {
            result += character;
        }
    }
    result += '"';
    return result;
}

void printOrder(const Order &order) {
    std::cout << "{\"id\":" << order.id << ",\"description\":" << jsonString(order.description)
              << ",\"attachment\":[";
    for (std::size_t index = 0; index < order.attachment.size(); ++index) {
        if (index != 0) {
            std::cout << ',';
        }
        std::cout << static_cast<unsigned int>(order.attachment[index]);
    }
    std::cout << "]}\n";
}

} // namespace

int main() {
    CLIENT *client = nullptr;
    try {
        // Resolve the provider URI supplied by the scenario, without rpcbind.
        const char *target = std::getenv("METEOR_ORDER_TARGET");
        target = target == nullptr ? "oncrpc://127.0.0.1:8080" : target;
        char host[256];
        char port[6];
        int consumed = 0;
        const int fields = std::sscanf(target, "oncrpc://%255[^:/]:%5[0-9]%n", host, port, &consumed);
        if (fields != 2 || target[consumed] != '\0' || std::atoi(port) < 1 || std::atoi(port) > 65535) {
            throw std::runtime_error("METEOR_ORDER_TARGET must be an oncrpc://host:port URI");
        }
        addrinfo hints{};
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_STREAM;
        hints.ai_flags = AI_NUMERICSERV;
        addrinfo *resolved = nullptr;
        const int error = getaddrinfo(host, port, &hints, &resolved);
        if (error != 0) {
            throw std::runtime_error(std::string("cannot resolve provider: ") + gai_strerror(error));
        }
        sockaddr_in address = *reinterpret_cast<sockaddr_in *>(resolved->ai_addr);
        freeaddrinfo(resolved);
        int socket = RPC_ANYSOCK;
        client = clnttcp_create(&address, ORDER_PROGRAM, ORDER_VERSION, &socket, 0, 0);
        if (client == nullptr) {
            throw std::runtime_error(clnt_spcreateerror("cannot connect to the order provider"));
        }

        // Adapt ordinary C++ values at the RPC boundary and save an order.
        Order order{1001, "meteor-order", {'M', 'e', 't', 'e', 'o', 'r'}};
        OrderDescription description = const_cast<char *>(order.description.c_str());
        OrderAttachment attachment{};
        attachment.OrderAttachment_len = static_cast<u_int>(order.attachment.size());
        attachment.OrderAttachment_val = reinterpret_cast<char *>(order.attachment.data());
        OrderValue request{order.id, &description, &attachment};
        OrderValue *saved = order_save_1(&request, client);
        if (saved == nullptr) {
            throw std::runtime_error(clnt_sperror(client, "save failed"));
        }

        xdr_free(reinterpret_cast<xdrproc_t>(xdr_OrderValue), reinterpret_cast<char *>(saved));

        // Query the same order and display the ordinary C++ DTO as JSON.
        OrderValue *found = order_find_1(&order.id, client);
        if (found == nullptr) {
            throw std::runtime_error(clnt_sperror(client, "find failed"));
        }

        const auto foundOrder = fromWire(*found);
        xdr_free(reinterpret_cast<xdrproc_t>(xdr_OrderValue), reinterpret_cast<char *>(found));

        printOrder(foundOrder);
        clnt_destroy(client);
        return 0;
    } catch (const std::exception &error) {
        if (client != nullptr) {
            clnt_destroy(client);
        }
        std::cerr << error.what() << '\n';
        return 1;
    }
}
