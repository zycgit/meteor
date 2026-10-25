CC ?= cc
RPCGEN ?= rpcgen
BUILD_DIR ?= ../build/native/c
BUILD := $(abspath $(BUILD_DIR))
CONTRACT := $(abspath ../resource/order.x)
CFLAGS ?= -O2 -Wall -Wextra
CPPFLAGS += -D_DEFAULT_SOURCE -I/usr/include/tirpc -I$(BUILD)
LDLIBS += -ltirpc

.PHONY: all clean
all: $(BUILD)/oncrpc/provider $(BUILD)/oncrpc/consumer

$(BUILD)/order.h: $(CONTRACT)
	@mkdir -p $(BUILD)/oncrpc
	$(RPCGEN) -C -h $< > $@

$(BUILD)/order_xdr.c: $(CONTRACT) $(BUILD)/order.h
	cd $(dir $(CONTRACT)) && $(RPCGEN) -C -c order.x > $@

$(BUILD)/order_svc.c: $(CONTRACT) $(BUILD)/order.h
	cd $(dir $(CONTRACT)) && $(RPCGEN) -C -m order.x > $@

$(BUILD)/order_clnt.c: $(CONTRACT) $(BUILD)/order.h
	cd $(dir $(CONTRACT)) && $(RPCGEN) -C -l order.x > $@

$(BUILD)/order_%.o: $(BUILD)/order_%.c
	$(CC) $(CPPFLAGS) $(CFLAGS) -Wno-unused-variable -Wno-cast-function-type -c $< -o $@

$(BUILD)/oncrpc/provider: oncrpc/provider.c $(BUILD)/order_svc.o $(BUILD)/order_xdr.o
	$(CC) $(CPPFLAGS) $(CFLAGS) $^ $(LDLIBS) -o $@

$(BUILD)/oncrpc/consumer: oncrpc/consumer.c $(BUILD)/order_clnt.o $(BUILD)/order_xdr.o
	$(CC) $(CPPFLAGS) $(CFLAGS) $^ $(LDLIBS) -o $@

clean:
	rm -rf $(BUILD)
