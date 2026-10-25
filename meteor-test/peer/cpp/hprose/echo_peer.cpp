/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
#include <hprose/rpc/Client.h>
#include <curl/curl.h>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

/* This is the upstream RPC client's protected, virtual transport customization point. */
class CurlHproseClient final : public hprose::rpc::Client {
public:
    explicit CurlHproseClient(const std::string &target) : hprose::rpc::Client(target) {
    }

protected:
    std::string sendAndReceive(const std::string &request, const hprose::rpc::ClientContext &context) override {
        // The SDK supplied the complete encoded request; libcurl only transports its bytes over HTTP.
        CURL *connection = curl_easy_init();
        if (connection == nullptr) {
            throw std::runtime_error("Cannot create the libcurl HTTP transport");
        }
        curl_slist *headers = curl_slist_append(nullptr, "Content-Type: application/hprose");
        std::string response;
        curl_easy_setopt(connection, CURLOPT_URL, this->uri.c_str());
        curl_easy_setopt(connection, CURLOPT_POST, 1L);
        curl_easy_setopt(connection, CURLOPT_HTTPHEADER, headers);
        curl_easy_setopt(connection, CURLOPT_POSTFIELDS, request.data());
        curl_easy_setopt(connection, CURLOPT_POSTFIELDSIZE_LARGE, static_cast<curl_off_t>(request.size()));
        curl_easy_setopt(connection, CURLOPT_TIMEOUT, static_cast<long>(context.settings.timeout));
        curl_easy_setopt(connection, CURLOPT_NOSIGNAL, 1L);
        curl_easy_setopt(connection, CURLOPT_WRITEFUNCTION, &CurlHproseClient::receive);
        curl_easy_setopt(connection, CURLOPT_WRITEDATA, &response);
        CURLcode result = curl_easy_perform(connection);
        long status = 0;
        curl_easy_getinfo(connection, CURLINFO_RESPONSE_CODE, &status);
        curl_slist_free_all(headers);
        curl_easy_cleanup(connection);
        if (result != CURLE_OK) {
            throw std::runtime_error(curl_easy_strerror(result));
        }
        if (status != 200) {
            throw std::runtime_error("Hprose HTTP status: " + std::to_string(status));
        }
        return response;
    }

private:
    static std::size_t receive(char *data, std::size_t size, std::size_t count, void *target) {
        const std::size_t length = size * count;
        static_cast<std::string *>(target)->append(data, length);
        return length;
    }
};

int main() {
    if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) {
        std::cerr << "Cannot initialize libcurl" << std::endl;
        return 1;
    }
    int exitCode = 0;
    try {
        const char *target = std::getenv("METEOR_ORDER_TARGET");
        if (target == nullptr) {
            throw std::runtime_error("METEOR_ORDER_TARGET is required");
        }

        // The unmodified official SDK owns RPC invocation and both Hprose serialization directions.
        CurlHproseClient client(target);
        client.retry = 0;
        client.timeout = 10;
        const std::string expected = "Hprose C++ source SDK 中文";
        const std::vector<std::string> arguments{expected};
        const std::string actual = client.invoke<std::string>("sample_echo", arguments);
        if (actual != expected) {
            throw std::runtime_error("Hprose C++ SDK echo mismatch: " + actual);
        }
        std::cout << "RESULT " << actual << std::endl;
    } catch (const std::exception &error) {
        std::cerr << error.what() << std::endl;
        exitCode = 1;
    }
    curl_global_cleanup();
    return exitCode;
}
