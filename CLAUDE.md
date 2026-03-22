# SIP Phone - Android VoIP App with Native AMR Codec

## Overview
Android VoIP phone app using Verto (WebSocket JSON-RPC) signaling to FreeSWITCH, with native AMR-NB/AMR-WB codec via OpenCORE-AMR/vo-amrwbenc NDK libraries. Features RTCP-based adaptive bitrate with AMR mode switching and CMR (Codec Mode Request).

## Architecture
```
Kotlin UI → Verto (WebSocket) → FreeSWITCH
         → JNI → Native C/C++:
                  ├── AMR Codec (OpenCORE-AMR + vo-amrwbenc)
                  ├── RTP/RTCP Engine (RFC 4867 octet-aligned)
                  ├── Jitter Buffer
                  └── Audio I/O (Oboe)
```

## Build Prerequisites
- Android Studio with NDK 26+ and CMake 3.22+
- JDK 21

## Build Steps

### 1. Build
```bash
./gradlew assembleDebug
```

### 2. Configure
Edit `CallActivity.kt` to set FreeSWITCH connection details:
- `serverUrl`: Verto WebSocket URL (wss://your-freeswitch:8082)
- `username`: Extension number
- `password`: Extension password

## Key Directories
```
app/src/main/
├── cpp/
│   ├── amr/              # AMR codec C wrapper
│   ├── rtp/              # RTP/RTCP engine, jitter buffer, AMR RTP payload (RFC 4867)
│   ├── audio/            # Oboe audio engine (C++)
│   ├── jni/              # JNI bridge
│   └── third-party/      # OpenCORE-AMR 0.1.6, vo-amrwbenc 0.1.3 (real sources integrated)
├── java/com/telcobright/sipphone/
│   ├── verto/            # Verto WebSocket client, SDP builder
│   ├── media/            # NativeMediaEngine (JNI), AdaptiveBitrateController, MediaSessionManager
│   ├── service/          # Foreground CallService
│   └── ui/               # CallActivity
└── res/                  # Layouts, themes, strings
```

## Common Commands
```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# View native logs
adb logcat -s AmrCodec:D RtpSession:D RtcpHandler:D AudioEngine:D JniBridge:D
```

## FreeSWITCH SDP Configuration
FreeSWITCH must have AMR codec enabled. In `vars.xml`:
```xml
<X-PRE-PROCESS cmd="set" data="global_codec_prefs=AMR-WB,AMR,PCMU,PCMA"/>
```

## Key Design Decisions
- **Octet-aligned mode** for AMR RTP payload (simpler parsing, `octet-align=1` in SDP fmtp)
- **Oboe** for audio I/O (lowest latency on Android, Google-maintained)
- **Adaptive bitrate** via RTCP Receiver Reports: smooth stepping (max 2 modes per adjustment)
- **CMR** (Codec Mode Request) in AMR RTP header for bidirectional mode negotiation
