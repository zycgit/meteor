/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */

const ORDER_MAX_DESCRIPTION = 4096;
const ORDER_MAX_ATTACHMENT = 65536;

typedef string OrderDescription<ORDER_MAX_DESCRIPTION>;
typedef opaque OrderAttachment<ORDER_MAX_ATTACHMENT>;

struct OrderValue {
    int id;
    OrderDescription *description;
    OrderAttachment *attachment;
};

program ORDER_PROGRAM {
    version ORDER_VERSION {
        OrderValue ORDER_SAVE(OrderValue) = 1;
        OrderValue ORDER_FIND(int) = 2;
    } = 1;
} = 0x31234569;
