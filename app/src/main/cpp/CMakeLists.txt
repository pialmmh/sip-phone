cmake_minimum_required(VERSION 3.22.1)
project("sipphone" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third-party)

# =============================================================================
# OpenCORE-AMR NB (encoder + decoder + wrapper)
# =============================================================================
set(OPENCORE_AMR_DIR ${THIRD_PARTY_DIR}/opencore-amr)

file(GLOB AMRNB_COMMON_SRC ${OPENCORE_AMR_DIR}/amr_nb/common/src/*.cpp)
file(GLOB AMRNB_DEC_SRC    ${OPENCORE_AMR_DIR}/amr_nb/dec/src/*.cpp)
file(GLOB AMRNB_ENC_SRC    ${OPENCORE_AMR_DIR}/amr_nb/enc/src/*.cpp)

add_library(opencore-amrnb STATIC
    ${OPENCORE_AMR_DIR}/wrapper.cpp
    ${AMRNB_COMMON_SRC}
    ${AMRNB_DEC_SRC}
    ${AMRNB_ENC_SRC}
)

target_include_directories(opencore-amrnb PUBLIC
    ${OPENCORE_AMR_DIR}
    ${OPENCORE_AMR_DIR}/oscl
    ${OPENCORE_AMR_DIR}/amr_nb/common/include
    ${OPENCORE_AMR_DIR}/amr_nb/dec/include
    ${OPENCORE_AMR_DIR}/amr_nb/dec/src
    ${OPENCORE_AMR_DIR}/amr_nb/enc/include
    ${OPENCORE_AMR_DIR}/amr_nb/enc/src
)

target_compile_options(opencore-amrnb PRIVATE
    -DOSCL_UNUSED_ARG=\(void\)
    -Wno-unused-parameter
    -Wno-unused-variable
)

# =============================================================================
# vo-amrwbenc (AMR-WB encoder + wrapper)
# =============================================================================
set(VO_AMRWBENC_DIR ${THIRD_PARTY_DIR}/vo-amrwbenc)

file(GLOB VO_AMRWBENC_SRC ${VO_AMRWBENC_DIR}/src/*.c)

add_library(vo-amrwbenc STATIC
    ${VO_AMRWBENC_DIR}/wrapper.c
    ${VO_AMRWBENC_DIR}/common/cmnMemory.c
    ${VO_AMRWBENC_SRC}
)

target_include_directories(vo-amrwbenc PUBLIC
    ${VO_AMRWBENC_DIR}
    ${VO_AMRWBENC_DIR}/inc
    ${VO_AMRWBENC_DIR}/common/include
)

target_compile_options(vo-amrwbenc PRIVATE
    -Wno-unused-parameter
    -Wno-unused-variable
)

# =============================================================================
# OpenCORE AMR-WB decoder + wrapper
# =============================================================================
set(OPENCORE_AMRWB_DIR ${THIRD_PARTY_DIR}/opencore-amrwb)

file(GLOB AMRWB_DEC_SRC ${OPENCORE_AMRWB_DIR}/amr_wb/dec/src/*.cpp)

add_library(opencore-amrwb STATIC
    ${OPENCORE_AMRWB_DIR}/wrapper.cpp
    ${AMRWB_DEC_SRC}
)

target_include_directories(opencore-amrwb PUBLIC
    ${OPENCORE_AMRWB_DIR}
    ${OPENCORE_AMRWB_DIR}/oscl
    ${OPENCORE_AMRWB_DIR}/amr_wb/dec/include
    ${OPENCORE_AMRWB_DIR}/amr_wb/dec/src
    ${OPENCORE_AMRWB_DIR}/common/dec/include
)

target_compile_options(opencore-amrwb PRIVATE
    -DOSCL_UNUSED_ARG=\(void\)
    -Wno-unused-parameter
    -Wno-unused-variable
)

# =============================================================================
# Oboe (low-latency audio I/O)
# =============================================================================
find_package(oboe REQUIRED CONFIG)

# =============================================================================
# SIP Phone native library
# =============================================================================
add_library(sipphone-native SHARED
    # AMR codec wrapper
    amr/amr_codec.c
    amr/amr_codec.h

    # RTP/RTCP engine
    rtp/rtp_session.c
    rtp/rtp_session.h
    rtp/rtp_packet.c
    rtp/rtp_packet.h
    rtp/rtcp_handler.c
    rtp/rtcp_handler.h
    rtp/amr_rtp_payload.c
    rtp/amr_rtp_payload.h
    rtp/jitter_buffer.c
    rtp/jitter_buffer.h

    # Audio engine (Oboe)
    audio/audio_engine.cpp
    audio/audio_engine.h

    # JNI bridge
    jni/jni_bridge.cpp
)

target_include_directories(sipphone-native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${OPENCORE_AMR_DIR}
    ${VO_AMRWBENC_DIR}
    ${OPENCORE_AMRWB_DIR}
)

target_link_libraries(sipphone-native
    opencore-amrnb
    vo-amrwbenc
    opencore-amrwb
    oboe::oboe
    android
    log
)
