JAVA_HOME ?= $(shell dirname $(shell dirname $(shell readlink -f $(shell which java))))
JNI_INCLUDE = $(JAVA_HOME)/include
JNI_INCLUDE_LINUX = $(JAVA_HOME)/include/linux

CC = gcc
CFLAGS = -fPIC -O2 -Wall -Wno-unused-parameter \
         -I$(JNI_INCLUDE) -I$(JNI_INCLUDE_LINUX) \
         -Isrc -Isrc/compat \
         -Iinclude/opencore-amrnb -Iinclude/opencore-amrwb -Iinclude/vo-amrwbenc

LDFLAGS = -shared
LIBS = -Llib -lopencore-amrnb -lopencore-amrwb -lvo-amrwbenc -lm -lpthread

# pjmedia backend (enabled by default if libs present)
CFLAGS += -DHAVE_PJMEDIA -Iinclude/pjmedia
LIBS += -Llib/pjmedia \
        -lpjmedia-x86_64-unknown-linux-gnu \
        -lpjnath-x86_64-unknown-linux-gnu \
        -lpjlib-util-x86_64-unknown-linux-gnu \
        -lpj-x86_64-unknown-linux-gnu \
        -luuid -lsrtp2

SOURCES = src/jni/jni_bridge.c \
          src/codec/pcmu_codec.c \
          src/amr/amr_codec.c \
          src/rtp/rtp_session.c \
          src/rtp/rtp_packet.c \
          src/rtp/rtcp_handler.c \
          src/rtp/amr_rtp_payload.c \
          src/rtp/jitter_buffer.c \
          src/rtp/rtp_transport_simple.c \
          src/rtp/rtp_transport_pjmedia.c \
          src/rtp/rtp_transport_registry.c

TARGET = libsipphone-native.so

all: $(TARGET)

$(TARGET): $(SOURCES)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $^ $(LIBS)

clean:
	rm -f $(TARGET)

.PHONY: all clean
