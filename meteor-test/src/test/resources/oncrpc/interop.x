/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */

const ONC_MAX_TEXT = 4096;
const ONC_MAX_BYTES = 65536;
const ONC_MAX_NUMBERS = 128;

typedef string onc_text<ONC_MAX_TEXT>;
typedef opaque onc_bytes<ONC_MAX_BYTES>;
typedef int onc_numbers<ONC_MAX_NUMBERS>;

enum onc_colour {
    ONC_RED = 2,
    ONC_GREEN = 3,
    ONC_BLUE = 5
};

union onc_choice switch (int tag) {
case 1:
    int number;
case 2:
    onc_text text;
default:
    void;
};

struct onc_optional {
    int value;
    struct onc_optional *next;
};

struct onc_pair {
    int left;
    int right;
};

struct onc_sample {
    int signed_value;
    unsigned int unsigned_value;
    hyper signed_hyper;
    unsigned hyper unsigned_hyper;
    float float_value;
    double double_value;
    bool bool_value;
    onc_text text;
    opaque fixed_opaque[5];
    onc_bytes bytes;
    int fixed_numbers[3];
    onc_numbers numbers;
    onc_colour colour;
    onc_choice choice;
    onc_optional *optional;
};

program ONC_INTEROP {
    version ONC_INTEROP_VERSION {
        onc_text ONC_ECHO(onc_text) = 1;
        int ONC_ADD(onc_pair) = 2;
        onc_sample ONC_MIRROR(onc_sample) = 3;
        void ONC_PING(void) = 4;
        void ONC_CRASH(void) = 5;
        onc_text ONC_AUTH(void) = 6;
        int ONC_DELAY(int) = 7;
        int ONC_INCREMENT(int) = 8;
        int ONC_COUNT(void) = 9;
        void ONC_RESET(void) = 10;
        onc_bytes ONC_BYTES(onc_bytes) = 11;
    } = 1;
} = 0x31234567;
