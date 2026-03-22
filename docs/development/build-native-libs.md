# Building Native Libraries

## Overview
Steps to replace stub codec files with real OpenCORE-AMR and vo-amrwbenc sources.

## Download Sources
```bash
# OpenCORE-AMR (AMR-NB encode/decode + AMR-WB decode)
wget https://sourceforge.net/projects/opencore-amr/files/opencore-amr/opencore-amr-0.1.6.tar.gz
tar xzf opencore-amr-0.1.6.tar.gz

# vo-amrwbenc (AMR-WB encoder)
wget https://sourceforge.net/projects/opencore-amr/files/vo-amrwbenc/vo-amrwbenc-0.1.3.tar.gz
tar xzf vo-amrwbenc-0.1.3.tar.gz
```

## Integration Steps

### AMR-NB (OpenCORE-AMR)
1. Copy `opencore-amr-0.1.6/amrnb/` source files to `app/src/main/cpp/third-party/opencore-amr/`
2. Update `CMakeLists.txt` to list all `.c` source files
3. Ensure `interf_enc.h` and `interf_dec.h` match the real headers

### AMR-WB Encoder (vo-amrwbenc)
1. Copy `vo-amrwbenc-0.1.3/amrwbenc/src/` to `app/src/main/cpp/third-party/vo-amrwbenc/`
2. Update `CMakeLists.txt` with all encoder `.c` files

### AMR-WB Decoder (OpenCORE-AMR)
1. Copy `opencore-amr-0.1.6/amrwb/` to `app/src/main/cpp/third-party/opencore-amrwb/`
2. Update `CMakeLists.txt` with all decoder `.c` files

## Verification
After replacing stubs, build and check logcat for "AmrCodec" initialization messages.
