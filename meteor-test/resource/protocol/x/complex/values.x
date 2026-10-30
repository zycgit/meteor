/* Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

const COMPLEX_TEXT_MAX = 256;
const COMPLEX_ENTRIES_MAX = 64;
typedef string complex_text<COMPLEX_TEXT_MAX>;

struct complex_customer {
    complex_text code;
    complex_text name;
    bool verified;
    double rating;
};

struct complex_line {
    complex_text sku;
    int quantity;
    double unit_price;
};

struct complex_document {
    int document_id;
    complex_customer customer;
    complex_line lines<COMPLEX_ENTRIES_MAX>;
    complex_text route<COMPLEX_ENTRIES_MAX>;
    complex_text receipt;
};

/* XDR represents each typed Map as a bounded array of key/value entries.
 * Keys must be unique. All fields are required; strings contain ASCII only.
 */
struct complex_line_entry {
    complex_text key;
    complex_line value;
};

struct complex_stock_entry {
    complex_text key;
    int value;
};

struct complex_index {
    int revision;
    complex_line_entry lines_by_code<COMPLEX_ENTRIES_MAX>;
    complex_stock_entry stock_by_code<COMPLEX_ENTRIES_MAX>;
    complex_text receipt;
};

program COMPLEX_VALUE_PROGRAM {
    version COMPLEX_VALUE_VERSION {
        complex_document ACCEPT_DOCUMENT(complex_document) = 1;
        complex_index ACCEPT_INDEX(complex_index) = 2;
    } = 1;
} = 0x31234892;
