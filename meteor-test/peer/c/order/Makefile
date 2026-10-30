ORDER_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
TEST_DIR := $(abspath $(ORDER_DIR)/../../..)
BUILD_DIR ?= $(TEST_DIR)/build/native/order
GEN_DIR := $(BUILD_DIR)/generated
CONTRACT := $(TEST_DIR)/resource/protocol/x/order.x

CC ?= cc
CXX ?= c++
RPCGEN ?= rpcgen
TIRPC_INCLUDE ?= /usr/include/tirpc
CPPFLAGS += -D_DEFAULT_SOURCE -I$(GEN_DIR) -I$(TIRPC_INCLUDE)
CFLAGS += -std=c11 -O2 -Wall -Wextra -Wno-unused-variable -Wno-cast-function-type
CXXFLAGS += -std=c++17 -O2 -Wall -Wextra
LDLIBS += -ltirpc

.PHONY: all clean
all: $(BUILD_DIR)/c/provider $(BUILD_DIR)/c/consumer $(BUILD_DIR)/cpp/provider $(BUILD_DIR)/cpp/consumer

$(GEN_DIR)/.generated: $(CONTRACT)
	mkdir -p $(GEN_DIR)
	cp $(CONTRACT) $(GEN_DIR)/order.x
	rm -f $(GEN_DIR)/order.h $(GEN_DIR)/order_svc.c $(GEN_DIR)/order_clnt.c $(GEN_DIR)/order_xdr.c
	cd $(GEN_DIR) && $(RPCGEN) -C -h -o order.h order.x
	cd $(GEN_DIR) && $(RPCGEN) -C -m -o order_svc.c order.x
	cd $(GEN_DIR) && $(RPCGEN) -C -l -o order_clnt.c order.x
	cd $(GEN_DIR) && $(RPCGEN) -C -c -o order_xdr.c order.x
	touch $@

$(BUILD_DIR)/order_%.o: $(GEN_DIR)/.generated
	$(CC) $(CPPFLAGS) $(CFLAGS) -c $(GEN_DIR)/order_$*.c -o $@

$(BUILD_DIR)/c/provider: $(ORDER_DIR)/provider.c $(BUILD_DIR)/order_svc.o $(BUILD_DIR)/order_xdr.o
	mkdir -p $(@D)
	$(CC) $(CPPFLAGS) $(CFLAGS) $^ $(LDLIBS) -o $@

$(BUILD_DIR)/c/consumer: $(ORDER_DIR)/consumer.c $(BUILD_DIR)/order_clnt.o $(BUILD_DIR)/order_xdr.o
	mkdir -p $(@D)
	$(CC) $(CPPFLAGS) $(CFLAGS) $^ $(LDLIBS) -o $@

$(BUILD_DIR)/cpp/provider: $(TEST_DIR)/peer/cpp/order/provider.cpp $(BUILD_DIR)/order_svc.o $(BUILD_DIR)/order_xdr.o
	mkdir -p $(@D)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $^ $(LDLIBS) -o $@

$(BUILD_DIR)/cpp/consumer: $(TEST_DIR)/peer/cpp/order/consumer.cpp $(BUILD_DIR)/order_clnt.o $(BUILD_DIR)/order_xdr.o
	mkdir -p $(@D)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $^ $(LDLIBS) -o $@

clean:
	rm -rf $(BUILD_DIR)
