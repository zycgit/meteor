/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
/* One invocation mutates and checks one typed XDR field; the existing IDL is unchanged. */
#ifndef METEOR_ONCRPC_TYPES_H
#define METEOR_ONCRPC_TYPES_H
#include <float.h>
#include <math.h>

static int create_type_sample(const char *vector, onc_sample *sample) {
    static char text[4097];
    static char bytes[256];
    static int numbers[128];
    static onc_optional head;
    static onc_optional tail;
    // The Docker C peer is LP64 on both supported Linux image architectures.
    _Static_assert(sizeof(long) == 8, "C type matrix expects an LP64 native peer");
    create_sample(sample);
    if (strcmp(vector, "i32-signed-char-min") == 0) {
        sample->signed_value = (signed char) SCHAR_MIN;
    } else if (strcmp(vector, "i32-signed-char-max") == 0) {
        sample->signed_value = (signed char) SCHAR_MAX;
    } else if (strcmp(vector, "i32-unsigned-char-max") == 0) {
        sample->signed_value = (unsigned char) UCHAR_MAX;
    } else if (strcmp(vector, "i32-short-min") == 0) {
        sample->signed_value = (short) SHRT_MIN;
    } else if (strcmp(vector, "i32-short-max") == 0) {
        sample->signed_value = (short) SHRT_MAX;
    } else if (strcmp(vector, "i32-unsigned-short-max") == 0) {
        sample->signed_value = (unsigned short) USHRT_MAX;
    } else if (strcmp(vector, "i64-long-min") == 0) {
        sample->signed_hyper = (long) LONG_MIN;
    } else if (strcmp(vector, "i64-long-max") == 0) {
        sample->signed_hyper = (long) LONG_MAX;
    } else if (strcmp(vector, "u64-unsigned-long-max") == 0) {
        sample->unsigned_hyper = (unsigned long) ULONG_MAX;
    } else if (strcmp(vector, "i32-min") == 0) {
        sample->signed_value = INT32_MIN;
    } else if (strcmp(vector, "i32-max") == 0) {
        sample->signed_value = INT32_MAX;
    } else if (strcmp(vector, "i32-zero") == 0) {
        sample->signed_value = 0;
    } else if (strcmp(vector, "u32-zero") == 0) {
        sample->unsigned_value = 0;
    } else if (strcmp(vector, "u32-high-bit") == 0) {
        sample->unsigned_value = UINT32_C(2147483648);
    } else if (strcmp(vector, "u32-max") == 0) {
        sample->unsigned_value = UINT32_MAX;
    } else if (strcmp(vector, "i64-min") == 0) {
        sample->signed_hyper = INT64_MIN;
    } else if (strcmp(vector, "i64-max") == 0) {
        sample->signed_hyper = INT64_MAX;
    } else if (strcmp(vector, "i64-zero") == 0) {
        sample->signed_hyper = 0;
    } else if (strcmp(vector, "u64-zero") == 0) {
        sample->unsigned_hyper = 0;
    } else if (strcmp(vector, "u64-high-bit") == 0) {
        sample->unsigned_hyper = UINT64_C(9223372036854775808);
    } else if (strcmp(vector, "u64-max") == 0) {
        sample->unsigned_hyper = UINT64_MAX;
    } else if (strcmp(vector, "f32-zero") == 0) {
        sample->float_value = 0.0f;
    } else if (strcmp(vector, "f32-negative-zero") == 0) {
        sample->float_value = -0.0f;
    } else if (strcmp(vector, "f32-precision") == 0) {
        sample->float_value = 0x1.000002p0f;
    } else if (strcmp(vector, "f32-max") == 0) {
        sample->float_value = FLT_MAX;
    } else if (strcmp(vector, "f32-negative-max") == 0) {
        sample->float_value = -FLT_MAX;
    } else if (strcmp(vector, "f32-min-normal") == 0) {
        sample->float_value = FLT_MIN;
    } else if (strcmp(vector, "f32-min-subnormal") == 0) {
        sample->float_value = 0x1p-149f;
    } else if (strcmp(vector, "f32-infinity") == 0) {
        sample->float_value = INFINITY;
    } else if (strcmp(vector, "f32-negative-infinity") == 0) {
        sample->float_value = -INFINITY;
    } else if (strcmp(vector, "f32-nan") == 0) {
        sample->float_value = NAN;
    } else if (strcmp(vector, "f64-zero") == 0) {
        sample->double_value = 0.0;
    } else if (strcmp(vector, "f64-negative-zero") == 0) {
        sample->double_value = -0.0;
    } else if (strcmp(vector, "f64-precision") == 0) {
        sample->double_value = 0x1.0000000000001p0;
    } else if (strcmp(vector, "f64-max") == 0) {
        sample->double_value = DBL_MAX;
    } else if (strcmp(vector, "f64-negative-max") == 0) {
        sample->double_value = -DBL_MAX;
    } else if (strcmp(vector, "f64-min-normal") == 0) {
        sample->double_value = DBL_MIN;
    } else if (strcmp(vector, "f64-min-subnormal") == 0) {
        sample->double_value = 0x1p-1074;
    } else if (strcmp(vector, "f64-infinity") == 0) {
        sample->double_value = INFINITY;
    } else if (strcmp(vector, "f64-negative-infinity") == 0) {
        sample->double_value = -INFINITY;
    } else if (strcmp(vector, "f64-nan") == 0) {
        sample->double_value = NAN;
    } else if (strcmp(vector, "bool-true") == 0) {
        sample->bool_value = TRUE;
    } else if (strcmp(vector, "bool-false") == 0) {
        sample->bool_value = FALSE;
    } else if (strcmp(vector, "string-empty") == 0) {
        sample->text = "";
    } else if (strcmp(vector, "string-ascii") == 0) {
        sample->text = "Meteor ASCII !\"#~";
    } else if (strcmp(vector, "string-max") == 0) {
        memset(text, 'x', 4096);
        text[4096] = '\0';
        sample->text = text;
    } else if (strcmp(vector, "opaque-empty") == 0) {
        sample->bytes.onc_bytes_len = 0;
    } else if (strcmp(vector, "opaque-all-bytes") == 0) {
        for (int index = 0; index < 256; index++) {
            bytes[index] = (char) index;
        }
        sample->bytes.onc_bytes_val = bytes;
        sample->bytes.onc_bytes_len = 256;
    } else if (strcmp(vector, "fixed-opaque-high-bits") == 0) {
        memcpy(sample->fixed_opaque, "\x00\x7f\x80\xfe\xff", 5);
    } else if (strcmp(vector, "array-empty") == 0) {
        sample->numbers.onc_numbers_len = 0;
    } else if (strcmp(vector, "array-boundaries") == 0) {
        numbers[0] = INT_MIN;
        numbers[1] = 0;
        numbers[2] = INT_MAX;
        sample->numbers.onc_numbers_val = numbers;
        sample->numbers.onc_numbers_len = 3;
    } else if (strcmp(vector, "array-max") == 0) {
        for (int index = 0; index < 128; index++) {
            numbers[index] = index - 64;
        }
        sample->numbers.onc_numbers_val = numbers;
        sample->numbers.onc_numbers_len = 128;
    } else if (strcmp(vector, "fixed-array-boundaries") == 0) {
        sample->fixed_numbers[0] = INT_MIN;
        sample->fixed_numbers[1] = 0;
        sample->fixed_numbers[2] = INT_MAX;
    } else if (strcmp(vector, "enum-red") == 0) {
        sample->colour = ONC_RED;
    } else if (strcmp(vector, "enum-green") == 0) {
        sample->colour = ONC_GREEN;
    } else if (strcmp(vector, "enum-blue") == 0) {
        sample->colour = ONC_BLUE;
    } else if (strcmp(vector, "union-int") == 0) {
        sample->choice.tag = 1;
        sample->choice.onc_choice_u.number = INT_MIN;
    } else if (strcmp(vector, "union-text") == 0) {
        sample->choice.tag = 2;
        sample->choice.onc_choice_u.text = "union-text";
    } else if (strcmp(vector, "union-default") == 0) {
        sample->choice.tag = 99;
    } else if (strcmp(vector, "pointer-null") == 0) {
        sample->optional = NULL;
    } else if (strcmp(vector, "pointer-single") == 0) {
        head.value = INT_MIN;
        head.next = NULL;
        sample->optional = &head;
    } else if (strcmp(vector, "pointer-recursive") == 0) {
        head.value = INT_MIN;
        head.next = &tail;
        tail.value = INT_MAX;
        tail.next = NULL;
        sample->optional = &head;
    } else if (strcmp(vector, "string-unicode-rejected") == 0) {
        sample->text = "\xe4\xb8\xad\xe6\x96\x87";
    } else {
        fprintf(stderr, "Unknown typed vector: %s\n", vector);
        return -1;
    }
    return 0;
}

static int type_field_equal(const char *vector, const onc_sample *left, const onc_sample *right) {
    if (strncmp(vector, "i32-", 4) == 0) {
        return left->signed_value == right->signed_value;
    } else if (strncmp(vector, "u32-", 4) == 0) {
        return left->unsigned_value == right->unsigned_value;
    } else if (strncmp(vector, "i64-", 4) == 0) {
        return left->signed_hyper == right->signed_hyper;
    } else if (strncmp(vector, "u64-", 4) == 0) {
        return left->unsigned_hyper == right->unsigned_hyper;
    } else if (strncmp(vector, "f32-", 4) == 0) {
        return memcmp(&left->float_value, &right->float_value, sizeof(float)) == 0;
    } else if (strncmp(vector, "f64-", 4) == 0) {
        return memcmp(&left->double_value, &right->double_value, sizeof(double)) == 0;
    } else if (strncmp(vector, "bool-", 5) == 0) {
        return left->bool_value == right->bool_value;
    } else if (strncmp(vector, "string-", 7) == 0) {
        return strcmp(left->text, right->text) == 0;
    } else if (strncmp(vector, "opaque-", 7) == 0) {
        return left->bytes.onc_bytes_len == right->bytes.onc_bytes_len
                && (left->bytes.onc_bytes_len == 0
                    || memcmp(left->bytes.onc_bytes_val, right->bytes.onc_bytes_val, left->bytes.onc_bytes_len) == 0);
    } else if (strncmp(vector, "fixed-opaque-", 13) == 0) {
        return memcmp(left->fixed_opaque, right->fixed_opaque, sizeof(left->fixed_opaque)) == 0;
    } else if (strncmp(vector, "array-", 6) == 0) {
        return left->numbers.onc_numbers_len == right->numbers.onc_numbers_len
                && (left->numbers.onc_numbers_len == 0
                    || memcmp(left->numbers.onc_numbers_val, right->numbers.onc_numbers_val,
                            left->numbers.onc_numbers_len * sizeof(int)) == 0);
    } else if (strncmp(vector, "fixed-array-", 12) == 0) {
        return memcmp(left->fixed_numbers, right->fixed_numbers, sizeof(left->fixed_numbers)) == 0;
    } else if (strncmp(vector, "enum-", 5) == 0) {
        return left->colour == right->colour;
    } else if (strncmp(vector, "union-", 6) == 0) {
        if (left->choice.tag != right->choice.tag) {
            return 0;
        }
        if (left->choice.tag == 1) {
            return left->choice.onc_choice_u.number == right->choice.onc_choice_u.number;
        }
        if (left->choice.tag == 2) {
            return strcmp(left->choice.onc_choice_u.text, right->choice.onc_choice_u.text) == 0;
        }
        return 1;
    } else if (strncmp(vector, "pointer-", 8) == 0) {
        const onc_optional *expected = left->optional;
        const onc_optional *actual = right->optional;
        while (expected != NULL && actual != NULL) {
            if (expected->value != actual->value) {
                return 0;
            }
            expected = expected->next;
            actual = actual->next;
        }
        return expected == NULL && actual == NULL;
    }
    return 0;
}

static int run_type_scenario(CLIENT *client, const struct peer_options *options) {
    // Each parameterized JUnit instance selects exactly one native field boundary.
    onc_sample argument;
    onc_sample response = {0};
    if (create_type_sample(options->value, &argument) != 0) {
        return -1;
    }
    int unicode = strcmp(options->value, "string-unicode-rejected") == 0;
    enum clnt_stat expected = unicode ? RPC_CANTDECODEARGS : RPC_SUCCESS;
    int result = checked_call(client, ONC_MIRROR, (xdrproc_t) xdr_onc_sample, &argument,
            (xdrproc_t) xdr_onc_sample, &response, expected, "types-unicode");
    if (result == 0 && !unicode && !type_field_equal(options->value, &argument, &response)) {
        fprintf(stderr, "Typed XDR vector differs: %s\n", options->value);
        result = -1;
    }
    if (result == 0 && !unicode) {
        printf("RESULT types=%s\n", options->value);
    }
    xdr_free((xdrproc_t) xdr_onc_sample, (char *) &response);
    return result;
}
#endif
