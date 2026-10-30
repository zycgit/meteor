/* Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

const SCALAR_TEXT_MAX = 256;
typedef string scalar_text<SCALAR_TEXT_MAX>;

enum scalar_kind {
    SCALAR_RETAIL = 0,
    SCALAR_WHOLESALE = 1
};

/* The boolean discriminant preserves both null and present empty strings. */
union scalar_optional_text switch (bool present) {
case TRUE:
    scalar_text value;
case FALSE:
    void;
};

struct scalar_value {
    bool flag;
    hyper integer;
    float single;
    double decimal;
    scalar_kind kind;
    scalar_optional_text note;
    scalar_text receipt;
};

/* Java Date maps to signed milliseconds since the Unix epoch. */
struct scalar_moment {
    hyper instant_epoch_millis;
    scalar_text receipt;
};

/* XDR has no arbitrary-precision decimal primitive. The ASCII representation
 * is BigDecimal.toString(), including an exponent when needed to retain scale;
 * new BigDecimal(text) restores the exact value and scale without a double.
 * All strings are ASCII and limited to SCALAR_TEXT_MAX bytes. Only note is null.
 */
struct scalar_decimal {
    scalar_text amount_decimal;
    scalar_text receipt;
};

program SCALAR_VALUE_PROGRAM {
    version SCALAR_VALUE_VERSION {
        scalar_value ECHO(scalar_value) = 1;
        scalar_moment ECHO_MOMENT(scalar_moment) = 2;
        scalar_decimal ECHO_DECIMAL(scalar_decimal) = 3;
    } = 1;
} = 0x31234893;
