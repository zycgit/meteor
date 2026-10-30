CPP_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
EXAMPLE_DIR := $(abspath $(CPP_DIR)/../..)
BUILD_DIR ?= $(EXAMPLE_DIR)/build/native/cpp
GEN_DIR := $(BUILD_DIR)/generated
CONTRACT := $(EXAMPLE_DIR)/resource/order.x

CC ?= cc
CXX ?= c++
RPCGEN ?= rpcgen
TIRPC_INCLUDE ?= /usr/include/tirpc
CPPFLAGS += -I$(GEN_DIR) -I$(TIRPC_INCLUDE)
CFLAGS += -O2
CXXFLAGS += -std=c++17 -O2 -Wall -Wextra
LDLIBS += -ltirpc

.PHONY: all clean
all: $(BUILD_DIR)/oncrpc/provider $(BUILD_DIR)/oncrpc/consumer

$(GEN_DIR)/.generated: $(CONTRACT)
	mkdir -p $(GEN_DIR)
	cp $(CONTRACT) $(GEN_DIR)/order.x
	rm -f $(GEN_DIR)/order.h $(GEN_DIR)/order_svc.c $(GEN_DIR)/order_clnt.c $(GEN_DIR)/order_xdr.c
	cd $(GEN_DIR) && $(RPCGEN) -C -h -o order.h order.x
	cd $(GEN_DIR) && $(RPCGEN) -C -m -o order_svc.c order.x
	cd $(GEN_DIR) && $(RPCGEN) -C -l -o order_clnt.c order.x
	cd $(GEN_DIR) && $(RPCGEN) -C -c -o order_xdr.c order.x
	touch $@

$(BUILD_DIR)/provider.o: $(CPP_DIR)/oncrpc/provider.cpp $(GEN_DIR)/.generated
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) -c $< -o $@

$(BUILD_DIR)/consumer.o: $(CPP_DIR)/oncrpc/consumer.cpp $(GEN_DIR)/.generated
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) -c $< -o $@

$(BUILD_DIR)/order_svc.o: $(GEN_DIR)/.generated
	$(CC) $(CPPFLAGS) $(CFLAGS) -c $(GEN_DIR)/order_svc.c -o $@

$(BUILD_DIR)/order_clnt.o: $(GEN_DIR)/.generated
	$(CC) $(CPPFLAGS) $(CFLAGS) -c $(GEN_DIR)/order_clnt.c -o $@

$(BUILD_DIR)/order_xdr.o: $(GEN_DIR)/.generated
	$(CC) $(CPPFLAGS) $(CFLAGS) -c $(GEN_DIR)/order_xdr.c -o $@

$(BUILD_DIR)/oncrpc/provider: $(BUILD_DIR)/provider.o $(BUILD_DIR)/order_svc.o $(BUILD_DIR)/order_xdr.o
	mkdir -p $(@D)
	$(CXX) $^ $(LDLIBS) -o $@

$(BUILD_DIR)/oncrpc/consumer: $(BUILD_DIR)/consumer.o $(BUILD_DIR)/order_clnt.o $(BUILD_DIR)/order_xdr.o
	mkdir -p $(@D)
	$(CXX) $^ $(LDLIBS) -o $@

clean:
	rm -rf $(BUILD_DIR)
