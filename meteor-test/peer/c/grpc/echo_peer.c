/* Public gRPC Core C API: one unary StringValue echo in each independent role. */
#include <grpc/grpc.h>
#include <grpc/grpc_security.h>
#if __has_include(<grpc/credentials.h>)
#include <grpc/credentials.h>
#endif
#include <grpc/byte_buffer.h>
#include <grpc/byte_buffer_reader.h>
#include <grpc/slice.h>
#include <grpc/support/time.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "google/protobuf/wrappers.pb-c.h"

static const char METHOD[] = "/meteor.java.sdk.TextEchoService/echo";

static void require(int success, const char *operation) {
    if (!success) {
        fprintf(stderr, "gRPC Core C failed: %s\n", operation);
        exit(EXIT_FAILURE);
    }
}

static gpr_timespec deadline(void) {
    return gpr_time_add(gpr_now(GPR_CLOCK_MONOTONIC), gpr_time_from_seconds(30, GPR_TIMESPAN));
}

static void await_completion(grpc_completion_queue *queue, void *tag) {
    grpc_event event = grpc_completion_queue_next(queue, deadline(), NULL);
    if (event.type != GRPC_OP_COMPLETE || event.tag != tag || !event.success) {
        fprintf(stderr, "gRPC Core completion: type=%d success=%d\n", event.type, event.success);
        require(0, "completion queue");
    }
}

static void execute(grpc_call *call, grpc_completion_queue *queue, grpc_op *operations, size_t count) {
    grpc_call_error result = grpc_call_start_batch(call, operations, count, operations, NULL);
    require(result == GRPC_CALL_OK, "start batch");
    await_completion(queue, operations);
}

static grpc_byte_buffer *encode_text(const char *text) {
    // Only Protobuf message bytes are handled here; gRPC owns framing and HTTP/2.
    Google__Protobuf__StringValue message = GOOGLE__PROTOBUF__STRING_VALUE__INIT;
    message.value = (char *) text;
    size_t size = google__protobuf__string_value__get_packed_size(&message);
    grpc_slice slice = grpc_slice_malloc(size);
    google__protobuf__string_value__pack(&message, GRPC_SLICE_START_PTR(slice));
    grpc_byte_buffer *buffer = grpc_raw_byte_buffer_create(&slice, 1);
    grpc_slice_unref(slice);
    return buffer;
}

static Google__Protobuf__StringValue *decode_text(grpc_byte_buffer *buffer) {
    require(buffer != NULL, "missing response message");
    grpc_byte_buffer_reader reader;
    require(grpc_byte_buffer_reader_init(&reader, buffer), "message reader");
    grpc_slice content = grpc_byte_buffer_reader_readall(&reader);
    Google__Protobuf__StringValue *message = google__protobuf__string_value__unpack(
        NULL, GRPC_SLICE_LENGTH(content), GRPC_SLICE_START_PTR(content));
    grpc_slice_unref(content);
    grpc_byte_buffer_reader_destroy(&reader);
    require(message != NULL, "decode StringValue");
    return message;
}

static void close_queue(grpc_completion_queue *queue) {
    // Drain the queue after owned calls and channels have been released.
    grpc_completion_queue_shutdown(queue);
    grpc_event event = grpc_completion_queue_next(queue, deadline(), NULL);
    require(event.type == GRPC_QUEUE_SHUTDOWN, "completion queue shutdown");
    grpc_completion_queue_destroy(queue);
}

static void consume(const char *text) {
    const char *target = getenv("METEOR_ORDER_TARGET");
    require(target != NULL && strncmp(target, "grpc://", 7) == 0, "grpc target");

    // The official C channel establishes the cleartext HTTP/2 connection.
    grpc_completion_queue *queue = grpc_completion_queue_create_for_next(NULL);
    grpc_channel_credentials *credentials = grpc_insecure_credentials_create();
    grpc_channel *channel = grpc_channel_create(target + 7, credentials, NULL);
    grpc_channel_credentials_release(credentials);
    grpc_call *call = grpc_channel_create_call(channel, NULL, GRPC_PROPAGATE_DEFAULTS, queue,
                                              grpc_slice_from_static_string(METHOD), NULL, deadline(), NULL);
    grpc_byte_buffer *request = encode_text(text);
    grpc_byte_buffer *response = NULL;
    grpc_metadata_array headers;
    grpc_metadata_array trailers;
    grpc_metadata_array_init(&headers);
    grpc_metadata_array_init(&trailers);
    grpc_status_code status = GRPC_STATUS_UNKNOWN;
    grpc_slice details = grpc_empty_slice();

    // One batch performs the unary send, half close, response and final status.
    grpc_op operations[6] = {0};
    operations[0].op = GRPC_OP_SEND_INITIAL_METADATA;
    operations[1].op = GRPC_OP_SEND_MESSAGE;
    operations[1].data.send_message.send_message = request;
    operations[2].op = GRPC_OP_SEND_CLOSE_FROM_CLIENT;
    operations[3].op = GRPC_OP_RECV_INITIAL_METADATA;
    operations[3].data.recv_initial_metadata.recv_initial_metadata = &headers;
    operations[4].op = GRPC_OP_RECV_MESSAGE;
    operations[4].data.recv_message.recv_message = &response;
    operations[5].op = GRPC_OP_RECV_STATUS_ON_CLIENT;
    operations[5].data.recv_status_on_client.trailing_metadata = &trailers;
    operations[5].data.recv_status_on_client.status = &status;
    operations[5].data.recv_status_on_client.status_details = &details;
    execute(call, queue, operations, 6);
    if (status != GRPC_STATUS_OK) {
        fprintf(stderr, "gRPC Core status=%d details=%.*s\n", status,
                (int) GRPC_SLICE_LENGTH(details), GRPC_SLICE_START_PTR(details));
        require(0, "unary status");
    }
    Google__Protobuf__StringValue *result = decode_text(response);
    require(strcmp(result->value, text) == 0, "echo result");
    printf("RESULT %s\n", result->value);

    // Each role releases its own message, call, channel and completion queue.
    google__protobuf__string_value__free_unpacked(result, NULL);
    grpc_byte_buffer_destroy(response);
    grpc_byte_buffer_destroy(request);
    grpc_slice_unref(details);
    grpc_metadata_array_destroy(&headers);
    grpc_metadata_array_destroy(&trailers);
    grpc_call_unref(call);
    grpc_channel_destroy(channel);
    close_queue(queue);
}

static void provide(void) {
    // Register exactly the standard method used by the shared test contract.
    grpc_completion_queue *queue = grpc_completion_queue_create_for_next(NULL);
    grpc_server *server = grpc_server_create(NULL, NULL);
    grpc_server_register_completion_queue(server, queue, NULL);
    void *method = grpc_server_register_method(server, METHOD, NULL,
                                               GRPC_SRM_PAYLOAD_READ_INITIAL_BYTE_BUFFER, 0);
    require(method != NULL, "register unary method");
    grpc_server_credentials *credentials = grpc_insecure_server_credentials_create();
    int port = grpc_server_add_http2_port(server, "0.0.0.0:0", credentials);
    grpc_server_credentials_release(credentials);
    require(port != 0, "listen");
    grpc_server_start(server);
    const char *hostname = getenv("METEOR_TEST_PEER_HOST");
    if (hostname == NULL) {
        hostname = "127.0.0.1";
    }
    printf("READY grpc://%s:%d\n", hostname, port);

    // Accept one independent unary scenario and decode its generated StringValue.
    grpc_call *call = NULL;
    grpc_byte_buffer *request = NULL;
    grpc_metadata_array headers;
    grpc_metadata_array_init(&headers);
    gpr_timespec call_deadline;
    grpc_call_error accepted = grpc_server_request_registered_call(server, method, &call, &call_deadline,
            &headers, &request, queue, queue, server);
    require(accepted == GRPC_CALL_OK, "request registered call");
    await_completion(queue, server);
    Google__Protobuf__StringValue *message = decode_text(request);
    grpc_byte_buffer *response = encode_text(message->value);

    // Core handles response metadata, HTTP/2 messages, status and peer half close.
    grpc_op operations[4] = {0};
    int cancelled = 1;
    operations[0].op = GRPC_OP_SEND_INITIAL_METADATA;
    operations[1].op = GRPC_OP_SEND_MESSAGE;
    operations[1].data.send_message.send_message = response;
    operations[2].op = GRPC_OP_SEND_STATUS_FROM_SERVER;
    operations[2].data.send_status_from_server.status = GRPC_STATUS_OK;
    operations[3].op = GRPC_OP_RECV_CLOSE_ON_SERVER;
    operations[3].data.recv_close_on_server.cancelled = &cancelled;
    execute(call, queue, operations, 4);
    require(!cancelled, "peer completed unary request");

    // The single-call provider finishes normally after the response is delivered.
    google__protobuf__string_value__free_unpacked(message, NULL);
    grpc_byte_buffer_destroy(response);
    grpc_byte_buffer_destroy(request);
    grpc_metadata_array_destroy(&headers);
    grpc_call_unref(call);
    grpc_server_shutdown_and_notify(server, queue, server);
    await_completion(queue, server);
    grpc_server_destroy(server);
    close_queue(queue);
}

int main(int count, char **arguments) {
    require(count >= 2, "provider or consumer role");
    setvbuf(stdout, NULL, _IOLBF, 0);
    grpc_init();
    if (strcmp(arguments[1], "provider") == 0) {
        provide();
    } else {
        require(strcmp(arguments[1], "consumer") == 0 && count == 3, "consumer text argument");
        consume(arguments[2]);
    }
    grpc_shutdown();
    return EXIT_SUCCESS;
}
