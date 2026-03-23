cmake_minimum_required(VERSION 3.22.1)
project("sipphone" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

set(PREBUILT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third-party/prebuilt)

# =============================================================================
# OpenCORE-AMR NB (prebuilt static library)
# =============================================================================
add_library(opencore-amrnb STATIC IMPORTED)
set_target_properties(opencore-amrnb PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/lib/${ANDROID_ABI}/libopencore-amrnb.a
    INTERFACE_INCLUDE_DIRECTORIES ${PREBUILT_DIR}/include/opencore-amrnb
)

# =============================================================================
# OpenCORE AMR-WB decoder (prebuilt static library)
# =============================================================================
add_library(opencore-amrwb STATIC IMPORTED)
set_target_properties(opencore-amrwb PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/lib/${ANDROID_ABI}/libopencore-amrwb.a
    INTERFACE_INCLUDE_DIRECTORIES ${PREBUILT_DIR}/include/opencore-amrwb
)

# =============================================================================
# vo-amrwbenc (prebuilt static library)
# =============================================================================
add_library(vo-amrwbenc STATIC IMPORTED)
set_target_properties(vo-amrwbenc PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/lib/${ANDROID_ABI}/libvo-amrwbenc.a
    INTERFACE_INCLUDE_DIRECTORIES ${PREBUILT_DIR}/include/vo-amrwbenc
)

# =============================================================================
# Oboe (low-latency audio I/O via prefab)
# =============================================================================
find_package(oboe REQUIRED CONFIG)

# =============================================================================
# SIP Phone native library
# =============================================================================
add_library(sipphone-native SHARED
    # AMR codec wrapper
    amr/amr_codec.c

    # RTP/RTCP engine
    rtp/rtp_session.c
    rtp/rtp_packet.c
    rtp/rtcp_handler.c
    rtp/amr_rtp_payload.c
    rtp/jitter_buffer.c

    # Audio engine (Oboe)
    audio/audio_engine.cpp

    # JNI bridge
    jni/jni_bridge.cpp
)

target_include_directories(sipphone-native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${PREBUILT_DIR}/include/opencore-amrnb
    ${PREBUILT_DIR}/include/opencore-amrwb
    ${PREBUILT_DIR}/include/vo-amrwbenc
)

target_link_libraries(sipphone-native
    opencore-amrnb
    vo-amrwbenc
    opencore-amrwb
    oboe::oboe
    android
    log
)
