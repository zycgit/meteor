/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
#include "OrderThriftService.h"
#include <thrift/protocol/TBinaryProtocol.h>
#include <thrift/server/TThreadedServer.h>
#include <thrift/transport/TBufferTransports.h>
#include <thrift/transport/TServerSocket.h>
#include <thrift/transport/TSocket.h>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

namespace {

using apache::thrift::protocol::TBinaryProtocol;
using apache::thrift::protocol::TBinaryProtocolFactory;
using apache::thrift::server::TThreadedServer;
using apache::thrift::transport::TFramedTransport;
using apache::thrift::transport::TFramedTransportFactory;
using apache::thrift::transport::TServerSocket;
using apache::thrift::transport::TSocket;

std::string environment(const char *name, const char *fallback) {
    const char *value = std::getenv(name);
    return value == nullptr ? fallback : value;
}

class OrderService final : public OrderThriftServiceIf {
    std::mutex mutex;
    std::map<int, ThriftOrder> orders;

    void save(ThriftOrder &response, const ThriftOrder &request) override {
        std::lock_guard<std::mutex> lock(mutex);
        orders[request.id] = request;
        response = request;
    }

    void find(ThriftOrder &response, const ThriftOrderQuery &request) override {
        std::lock_guard<std::mutex> lock(mutex);
        response = orders.at(request.id);
    }
};

class ReadyEvent final : public apache::thrift::server::TServerEventHandler {
    std::shared_ptr<TServerSocket> socket;
    std::string host;

public:
    ReadyEvent(std::shared_ptr<TServerSocket> socket, std::string host)
        : socket(std::move(socket)), host(std::move(host)) {
    }

    void preServe() override {
        // The server has called listen; getPort now reports the actual OS-selected port.
        std::cout << "Order provider is ready: thrift://" << host << ':' << socket->getPort() << std::endl;
    }
};

void provide() {
    // Binary encoding and framed transport match the ordinary Meteor Thrift defaults.
    const std::string host = environment("METEOR_ORDER_BIND_HOST", "127.0.0.1");
    const int port = std::stoi(environment("METEOR_ORDER_PORT", "0"));
    auto listener = std::make_shared<TServerSocket>(host, port);
    auto service = std::make_shared<OrderService>();
    auto processor = std::make_shared<OrderThriftServiceProcessor>(service);
    auto transports = std::make_shared<TFramedTransportFactory>();
    auto protocols = std::make_shared<TBinaryProtocolFactory>();
    // Meteor may use different persistent connections for save and find; serve both independently.
    TThreadedServer server(processor, listener, transports, protocols);
    const std::string publishHost = environment("METEOR_TEST_PEER_HOST", host.c_str());
    server.setServerEventHandler(std::make_shared<ReadyEvent>(listener, publishHost));
    server.serve();
}

void consume() {
    // Resolve the scenario's direct target without a registry or shared Java codec.
    std::string target = environment("METEOR_ORDER_TARGET", "thrift://127.0.0.1:8080");
    target = target.substr(target.find("://") + 3);
    const auto separator = target.rfind(':');
    auto socket = std::make_shared<TSocket>(target.substr(0, separator), std::stoi(target.substr(separator + 1)));
    socket->setConnTimeout(10000);
    socket->setSendTimeout(10000);
    socket->setRecvTimeout(10000);
    auto transport = std::make_shared<TFramedTransport>(socket);
    auto protocol = std::make_shared<TBinaryProtocol>(transport);
    OrderThriftServiceClient client(protocol);
    transport->open();

    // Save and find one order through generated SDK methods.
    ThriftOrder order;
    order.__set_id(1001);
    order.__set_description("meteor-order");
    order.__set_attachment("Meteor");
    ThriftOrder saved;
    client.save(saved, order);
    ThriftOrderQuery query;
    query.__set_id(order.id);
    ThriftOrder found;
    client.find(found, query);
    transport->close();

    // Binary data is emitted as an integer array and checked byte-for-byte by JUnit.
    std::cout << "{\"id\":" << found.id << ",\"description\":" << std::quoted(found.description)
              << ",\"attachment\":[";
    for (std::size_t index = 0; index < found.attachment.size(); ++index) {
        if (index != 0) {
            std::cout << ',';
        }
        std::cout << static_cast<unsigned int>(static_cast<unsigned char>(found.attachment[index]));
    }
    std::cout << "]}" << std::endl;
}

} // namespace

int main(int argc, char **argv) {
    try {
        if (argc != 2) {
            throw std::runtime_error("Expected provider or consumer role");
        }
        if (std::string(argv[1]) == "provider") {
            provide();
        } else if (std::string(argv[1]) == "consumer") {
            consume();
        } else {
            throw std::runtime_error("Unknown C++ Thrift role");
        }
    } catch (const std::exception &error) {
        std::cerr << error.what() << std::endl;
        return 1;
    }
}
