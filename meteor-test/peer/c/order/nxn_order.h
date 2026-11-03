/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
#ifndef METEOR_TEST_NXN_ORDER_H
#define METEOR_TEST_NXN_ORDER_H

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Process control only: SDKs still own all business request encoding. */
struct nxn_order_input {
    int32_t id;
    const char *description;
    unsigned char *attachment;
    size_t attachment_length;
};

static inline int nxn_order_enabled(void) {
    return getenv("METEOR_NXN_REQUEST_FILE") != NULL;
}

static inline int nxn_base64_digit(unsigned char value) {
    if (value >= 'A' && value <= 'Z') {
        return value - 'A';
    }
    if (value >= 'a' && value <= 'z') {
        return value - 'a' + 26;
    }
    if (value >= '0' && value <= '9') {
        return value - '0' + 52;
    }
    if (value == '+') {
        return 62;
    }
    if (value == '/') {
        return 63;
    }
    return -1;
}

static inline int nxn_order_read(struct nxn_order_input *input) {
    const char *id = getenv("METEOR_NXN_ORDER_ID");
    const char *description = getenv("METEOR_NXN_ORDER_DESCRIPTION");
    const char *attachment = getenv("METEOR_NXN_ORDER_ATTACHMENT");
    if (id == NULL || description == NULL || attachment == NULL) {
        fputs("NXN order requires id, description and attachment environment values\n", stderr);
        return 0;
    }
    char *end = NULL;
    errno = 0;
    const long parsed = strtol(id, &end, 10);
    if (errno != 0 || *id == '\0' || *end != '\0' || parsed < INT32_MIN || parsed > INT32_MAX) {
        fputs("NXN order id must be an int32\n", stderr);
        return 0;
    }
    const size_t length = strlen(attachment);
    if (length % 4 != 0) {
        fputs("NXN order attachment must be Base64\n", stderr);
        return 0;
    }
    unsigned char *bytes = (unsigned char *) malloc(length / 4 * 3 + 1);
    if (bytes == NULL) {
        fputs("Cannot allocate NXN order attachment\n", stderr);
        return 0;
    }
    size_t size = 0;
    for (size_t index = 0; index < length; index += 4) {
        const int first = nxn_base64_digit((unsigned char) attachment[index]);
        const int second = nxn_base64_digit((unsigned char) attachment[index + 1]);
        const int third = nxn_base64_digit((unsigned char) attachment[index + 2]);
        const int fourth = nxn_base64_digit((unsigned char) attachment[index + 3]);
        const int pad_third = attachment[index + 2] == '=';
        const int pad_fourth = attachment[index + 3] == '=';
        if (first < 0 || second < 0 || (third < 0 && !pad_third) || (fourth < 0 && !pad_fourth)
                || (pad_third && !pad_fourth) || ((pad_third || pad_fourth) && index + 4 != length)
                || (pad_third && (second & 15) != 0) || (!pad_third && pad_fourth && (third & 3) != 0)) {
            free(bytes);
            fputs("NXN order attachment must be canonical Base64\n", stderr);
            return 0;
        }
        bytes[size++] = (unsigned char) ((first << 2) | (second >> 4));
        if (!pad_third) {
            bytes[size++] = (unsigned char) ((second << 4) | (third >> 2));
        }
        if (!pad_fourth) {
            bytes[size++] = (unsigned char) ((third << 6) | fourth);
        }
    }
    input->id = (int32_t) parsed;
    input->description = description;
    input->attachment = bytes;
    input->attachment_length = size;
    return 1;
}

static inline void nxn_json_string(const char *value) {
    putchar('"');
    for (const unsigned char *cursor = (const unsigned char *) value; *cursor != '\0'; cursor++) {
        if (*cursor == '"' || *cursor == '\\') {
            putchar('\\');
            putchar(*cursor);
        } else if (*cursor < 32) {
            printf("\\u%04x", *cursor);
        } else {
            putchar(*cursor);
        }
    }
    putchar('"');
}

static inline void nxn_order_ready(const char *scheme, const char *host, unsigned int port) {
    fputs("{\"event\":\"ready\",\"target\":\"", stdout);
    printf("%s://%s:%u\"}\n", scheme, host, port);
    fflush(stdout);
}

static inline void nxn_order_json(int32_t id, const char *description,
                                 const unsigned char *attachment, size_t length) {
    static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    printf("{\"id\":%d,\"description\":", id);
    nxn_json_string(description);
    fputs(",\"attachment\":\"", stdout);
    for (size_t index = 0; index < length; index += 3) {
        const unsigned int first = attachment[index];
        const unsigned int second = index + 1 < length ? attachment[index + 1] : 0;
        const unsigned int third = index + 2 < length ? attachment[index + 2] : 0;
        putchar(alphabet[first >> 2]);
        putchar(alphabet[((first & 3) << 4) | (second >> 4)]);
        putchar(index + 1 < length ? alphabet[((second & 15) << 2) | (third >> 6)] : '=');
        putchar(index + 2 < length ? alphabet[third & 63] : '=');
    }
    fputs("\"}", stdout);
}

static inline void nxn_order_result(int32_t saved_id, const char *saved_description,
                                   const unsigned char *saved_attachment, size_t saved_length,
                                   int32_t found_id, const char *found_description,
                                   const unsigned char *found_attachment, size_t found_length) {
    fputs("{\"event\":\"result\",\"saved\":", stdout);
    nxn_order_json(saved_id, saved_description, saved_attachment, saved_length);
    fputs(",\"order\":", stdout);
    nxn_order_json(found_id, found_description, found_attachment, found_length);
    fputs("}\n", stdout);
    fflush(stdout);
}

#endif
