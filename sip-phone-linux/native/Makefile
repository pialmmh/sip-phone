JAVA_HOME ?= $(shell dirname $(shell dirname $(shell readlink -f $(shell which java))))
JNI_INCLUDE = $(JAVA_HOME)/include
JNI_INCLUDE_LINUX = $(JAVA_HOME)/include/linux

CC = gcc
CFLAGS = -fPIC -O2 -Wall -Wno-unused-parameter \
         -I$(JNI_INCLUDE) -I$(JNI_INCLUDE_LINUX) \
         -Isrc -Isrc/compat \
         -Iinclude/opencore-amrnb -Iinclude/opencore-amrwb -Iinclude/vo-amrwbenc \
         -Isrc/compat

LDFLAGS = -shared
LIBS = -Llib -lopencore-amrnb -lopencore-amrwb -lvo-amrwbenc -lm -lpthread

SOURCES = src/jni/jni_bridge.c \
          src/amr/amr_codec.c \
          src/rtp/rtp_session.c \
          src/rtp/rtp_packet.c \
          src/rtp/rtcp_handler.c \
          src/rtp/amr_rtp_payload.c \
          src/rtp/jitter_buffer.c

TARGET = libsipphone-native.so

all: $(TARGET)

$(TARGET): $(SOURCES)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $^ $(LIBS)

clean:
	rm -f $(TARGET)

.PHONY: all clean
