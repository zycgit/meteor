/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
#include "order.grpc.pb.h"
#include <grpcpp/grpcpp.h>
#include <google/protobuf/util/json_util.h>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <map>
#include <mutex>
#include <stdexcept>
#include <string>

namespace {

std::string environment(const char *name, const char *fallback) {
    const char *value = std::getenv(name);
    return value == nullptr ? fallback : value;
}

class OrderService final : public meteor::example::OrderDemoService::Service {
    std::mutex mutex;
    std::map<int, meteor::example::Order> orders;

    grpc::Status save(grpc::ServerContext *, const meteor::example::Order *request,
                      meteor::example::Order *response) override {
        std::lock_guard<std::mutex> lock(mutex);
        orders[request->id()] = *request;
        *response = *request;
        return grpc::Status::OK;
    }

    grpc::Status find(grpc::ServerContext *, const meteor::example::OrderQuery *request,
                      meteor::example::Order *response) override {
        std::lock_guard<std::mutex> lock(mutex);
        const auto found = orders.find(request->id());
        if (found == orders.end()) {
            return {grpc::StatusCode::NOT_FOUND, "Order not found"};
        }
        *response = found->second;
        return grpc::Status::OK;
    }
};

void provide() {
    // Let gRPC bind an OS-selected port, and announce it only after the server starts.
    const std::string host = environment("METEOR_ORDER_BIND_HOST", "127.0.0.1");
    const std::string port = environment("METEOR_ORDER_PORT", "0");
    OrderService service;
    grpc::ServerBuilder builder;
    int selectedPort = 0;
    builder.AddListeningPort(host + ":" + port, grpc::InsecureServerCredentials(), &selectedPort);
    builder.RegisterService(&service);
    auto server = builder.BuildAndStart();
    if (!server || selectedPort == 0) {
        throw std::runtime_error("C++ gRPC server did not start");
    }
    const std::string publishHost = environment("METEOR_TEST_PEER_HOST", host.c_str());
    std::cout << "Order provider is ready: grpc://" << publishHost << ':' << selectedPort << std::endl;
    server->Wait();
}

void consume() {
    // The Meteor test mounts gRPC at the standard root, so generated SDK methods need no path rewrite.
    std::string target = environment("METEOR_ORDER_TARGET", "grpc://127.0.0.1:8080");
    target = target.substr(target.find("://") + 3);
    auto channel = grpc::CreateChannel(target, grpc::InsecureChannelCredentials());
    auto client = meteor::example::OrderDemoService::NewStub(channel);
    meteor::example::Order order;
    order.set_id(1001);
    order.set_description("meteor-order");
    order.set_attachment("Meteor");

    // Save and query the same order through two generated unary methods.
    grpc::ClientContext saveContext;
    saveContext.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(10));
    meteor::example::Order saved;
    auto status = client->save(&saveContext, order, &saved);
    if (!status.ok()) {
        throw std::runtime_error("gRPC save failed: " + status.error_message());
    }
    grpc::ClientContext findContext;
    findContext.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(10));
    meteor::example::OrderQuery query;
    query.set_id(order.id());
    meteor::example::Order found;
    status = client->find(&findContext, query, &found);
    if (!status.ok()) {
        throw std::runtime_error("gRPC find failed: " + status.error_message());
    }

    // Protobuf JSON preserves the returned DTO and Base64 attachment for the Java-side assertions.
    std::string result;
    const auto jsonStatus = google::protobuf::util::MessageToJsonString(found, &result);
    if (!jsonStatus.ok()) {
        throw std::runtime_error("Cannot format the returned gRPC order");
    }
    std::cout << result << std::endl;
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
            throw std::runtime_error("Unknown C++ gRPC role");
        }
    } catch (const std::exception &error) {
        std::cerr << error.what() << std::endl;
        return 1;
    }
}
