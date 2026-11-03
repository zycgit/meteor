/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AddressBookTest {
    @Test
    void parsesEveryProviderAddressAsPlainData() throws Exception {
        // 多种协议的静态提供者地址均按原值解析，不附加协议策略。
        try (InputStream input = getClass().getResourceAsStream("/address-book/mixed-protocols.xml")) {
            assertNotNull(input);
            AddressBook book = AddressBook.parse(input);
            assertEquals(1, book.getServices().size());
            AddressBook.Service service = book.getServices().get(0);
            assertEquals("[billing]order-query-1.0", service.getServiceID());
            assertEquals(4, service.getAddresses().size());
            Set<String> schemes = new HashSet<>();
            service.getAddresses().forEach(address -> schemes.add(address.getSchema()));
            assertEquals(Set.of("rsf", "jsonrpc", "hessian"), schemes);
            assertThrows(UnsupportedOperationException.class, () -> service.getAddresses().clear());
            assertThrows(UnsupportedOperationException.class, () -> book.getServices().clear());
        }
    }

    @Test
    void parsesDtdProviderAddresses() throws Exception {
        // DTD 通过内置资源离线验证，解析结果仍是普通地址数据。
        try (InputStream input = getClass().getResourceAsStream("/address-book/dtd-public-offline.xml")) {
            assertNotNull(input);
            AddressBook.Service service = AddressBook.parse(input).getServices().get(0);
            assertEquals("[billing]order-query-1.0", service.getServiceID());
            assertEquals(2, service.getAddresses().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "unsupported-protocols-xsd.xml", "unsupported-protocols-dtd.xml" })
    void rejectsProtocolPolicyInAddressBook(String resource) throws Exception {
        // 地址本不承载协议策略，XSD 和 DTD 声明都必须拒绝此属性。
        try (InputStream input = getClass().getResourceAsStream("/address-book/" + resource)) {
            assertNotNull(input);
            var error = assertThrows(IOException.class, () -> AddressBook.parse(input));
            assertTrue(error.getMessage().contains("protocols"));
        }
    }
}
