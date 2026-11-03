/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
#include "order.grpc.pb.h"
#include "../../c/order/nxn_order.h"
#include <grpcpp/grpcpp.h>
#include <grpcpp/generic/generic_stub.h>
#include <google/protobuf/util/json_util.h>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <map>
#include <memory>
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
    if (nxn_order_enabled()) {
        nxn_order_ready("grpc", publishHost.c_str(), selectedPort);
    } else {
        std::cout << "Order provider is ready: grpc://" << publishHost << ':' << selectedPort << std::endl;
    }
    server->Wait();
}

template<typename Request, typename Response>
grpc::Status call(const std::shared_ptr<grpc::Channel> &channel, const std::string &method,
                  const Request &request, Response *response) {
    // Public generic SDK calls preserve the context path announced by another language's provider.
    grpc::TemplatedGenericStub<Request, Response> client(channel);
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(10));
    grpc::CompletionQueue completions;
    auto operation = client.PrepareUnaryCall(&context, method, request, &completions);
    if (!operation) {
        completions.Shutdown();
        void *tag = nullptr;
        bool completed = false;
        while (completions.Next(&tag, &completed)) {
        }
        throw std::runtime_error("gRPC unary call could not be prepared");
    }
    operation->StartCall();
    grpc::Status status;
    operation->Finish(response, &status, reinterpret_cast<void *>(1));
    void *tag = nullptr;
    bool completed = false;
    const bool received = completions.Next(&tag, &completed);
    completions.Shutdown();
    void *remainingTag = nullptr;
    bool remainingCompleted = false;
    while (completions.Next(&remainingTag, &remainingCompleted)) {
    }
    if (!received || !completed || tag != reinterpret_cast<void *>(1)) {
        throw std::runtime_error("gRPC unary completion was not received");
    }
    return status;
}

void consume() {
    // The Meteor test mounts gRPC at the standard root, so generated SDK methods need no path rewrite.
    std::string target = environment("METEOR_ORDER_TARGET", "grpc://127.0.0.1:8080");
    target = target.substr(target.find("://") + 3);
    std::string contextPath;
    if (nxn_order_enabled()) {
        const auto separator = target.find('/');
        if (separator != std::string::npos) {
            contextPath = target.substr(separator);
            target = target.substr(0, separator);
            while (!contextPath.empty() && contextPath.back() == '/') {
                contextPath.pop_back();
            }
        }
    }
    auto channel = grpc::CreateChannel(target, grpc::InsecureChannelCredentials());
    auto client = meteor::example::OrderDemoService::NewStub(channel);
    meteor::example::Order order;
    order.set_id(1001);
    order.set_description("meteor-order");
    order.set_attachment("Meteor");
    if (nxn_order_enabled()) {
        nxn_order_input input{};
        if (!nxn_order_read(&input)) {
            throw std::runtime_error("Cannot read NXN order input");
        }
        std::unique_ptr<unsigned char, decltype(&std::free)> bytes(input.attachment, &std::free);
        order.set_id(input.id);
        order.set_description(input.description);
        order.set_attachment(input.attachment, input.attachment_length);
    }

    // Save and query the same order through two generated unary methods.
    grpc::ClientContext saveContext;
    saveContext.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(10));
    meteor::example::Order saved;
    auto status = nxn_order_enabled()
            ? call(channel, contextPath + "/meteor.example.OrderDemoService/save", order, &saved)
            : client->save(&saveContext, order, &saved);
    if (!status.ok()) {
        throw std::runtime_error("gRPC save failed: " + status.error_message());
    }
    if (nxn_order_enabled() && (saved.id() != order.id() || saved.description() != order.description()
            || saved.attachment() != order.attachment())) {
        throw std::runtime_error("gRPC save returned a different NXN order");
    }
    grpc::ClientContext findContext;
    findContext.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(10));
    meteor::example::OrderQuery query;
    query.set_id(order.id());
    meteor::example::Order found;
    if (nxn_order_enabled()) {
        status = call(channel, contextPath + "/meteor.example.OrderDemoService/find", query, &found);
    } else {
        status = client->find(&findContext, query, &found);
    }
    if (!status.ok()) {
        throw std::runtime_error("gRPC find failed: " + status.error_message());
    }
    if (nxn_order_enabled()) {
        nxn_order_result(saved.id(), saved.description().c_str(),
                reinterpret_cast<const unsigned char *>(saved.attachment().data()), saved.attachment().size(),
                found.id(), found.description().c_str(),
                reinterpret_cast<const unsigned char *>(found.attachment().data()), found.attachment().size());
        return;
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
