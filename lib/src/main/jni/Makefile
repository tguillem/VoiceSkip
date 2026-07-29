PREFIX ?= /usr/local

CC      := gcc
CXX     := g++
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

# Linked with the C++ driver because whisper_guard.cpp is what keeps a backend
# exception from unwinding into stream.c.
stream_test: stream.o stream_test.o whisper_guard.o
	$(CXX) -o $@ $^ $(LDFLAGS)

%.o: %.c
	$(CC) $(CFLAGS) -c -o $@ $<

%.o: %.cpp
	$(CXX) $(CFLAGS) -c -o $@ $<

clean:
	rm -f stream_test *.o

.PHONY: clean
