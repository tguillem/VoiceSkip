PREFIX ?= /usr/local

CC      := gcc
CFLAGS  := -I$(PREFIX)/include -O2 -Wall
LDFLAGS := -L$(PREFIX)/lib -Wl,-rpath,$(PREFIX)/lib -lwhisper -lavformat -lavcodec -lavutil -lswresample -lpthread -lm

ifdef ASAN
CFLAGS  := -I$(PREFIX)/include -g -Og -fno-omit-frame-pointer -fsanitize=address -Wall
LDFLAGS += -fsanitize=address
endif

ifdef TSAN
CFLAGS  := -I$(PREFIX)/include -g -Og -fno-omit-frame-pointer -fsanitize=thread -Wall
LDFLAGS += -fsanitize=thread
endif

stream_test: stream.c stream_test.c
	$(CC) $(CFLAGS) -o $@ $^ $(LDFLAGS)

clean:
	rm -f stream_test

.PHONY: clean
