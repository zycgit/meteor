/* Loams source SDK's public generic unary client against Meteor's existing echo contract. */
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <curl/curl.h>
#include <google/protobuf/wrappers.pb.h>
#include <loams/client.hpp>
#include <loams/version.hpp>

int main(int argc, char **argv) {
    try {
        // Do not silently use native's unrelated protobuf/curl or the SDK's retry policy.
        if (argc != 2) {
            throw std::runtime_error("Usage: echo-peer text");
        }
        const char *target = std::getenv("METEOR_ORDER_TARGET");
        if (target == nullptr) {
            throw std::runtime_error("METEOR_ORDER_TARGET is required");
        }
        if (loams::SdkVersion() != "0.1.3" || curl_version_info(CURLVERSION_NOW)->version_num != 0x081600) {
            throw std::runtime_error("Expected isolated Loams 0.1.3 and curl 8.22.0");
        }
        loams::Options options;
        options.endpoint = target;
        options.timeout = std::chrono::milliseconds(10000);
        options.max_retries = 0;
        auto client = loams::Client::Make(options);

        // The SDK itself encodes, transmits and decodes the standard protobuf wrapper.
        const std::string text = argv[1];
        google::protobuf::StringValue request;
        request.set_value(text);
        google::protobuf::StringValue response;
        loams::MethodBinding method;
        method.rpc = "meteor.java.sdk.TextEchoService/echo";
        method.path = "/rpc/" + method.rpc;
        client->Unary(method, request, &response, loams::ContentType::kProto);

        // The suffix proves the result was produced by the remote Meteor implementation.
        if (response.value() != text + "|meteor") {
            throw std::runtime_error("Loams Connect echo mismatch: " + response.value());
        }
        std::cout << "OK Loams " << loams::SdkVersion() << ": " << response.value() << std::endl;
        return EXIT_SUCCESS;
    } catch (const std::exception &failure) {
        std::cerr << "Loams Connect failure: " << failure.what() << std::endl;
        return EXIT_FAILURE;
    }
}
