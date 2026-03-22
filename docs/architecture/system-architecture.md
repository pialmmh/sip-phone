# System Architecture

## Overview
Android VoIP application using native AMR codec with Verto signaling to FreeSWITCH.

## Component Diagram
```
┌─────────────────────────────────────────────────────────┐
│                    Android App                           │
│                                                          │
│  ┌─────────────┐  ┌───────────────┐  ┌───────────────┐ │
│  │  CallActivity│  │MediaSession   │  │ Adaptive      │ │
│  │  (UI)        │──│Manager        │──│ Bitrate Ctrl  │ │
│  └──────┬───────┘  └───────┬───────┘  └───────┬───────┘ │
│         │                  │                   │         │
│  ┌──────┴───────┐  ┌──────┴───────┐           │         │
│  │ VertoClient  │  │NativeMedia   │           │         │
│  │ (WebSocket)  │  │Engine (JNI)  │◄──────────┘         │
│  └──────┬───────┘  └──────┬───────┘                     │
│         │                  │ JNI                         │
│─────────┼──────────────────┼─────────────────────────────│
│  NATIVE │                  │                             │
│         │          ┌───────┴───────┐                     │
│         │          │  RTP Session  │                     │
│         │          │  ┌─────────┐  │                     │
│         │          │  │AMR Codec│  │                     │
│         │          │  └─────────┘  │                     │
│         │          │  ┌─────────┐  │                     │
│         │          │  │RTCP Hdlr│  │                     │
│         │          │  └─────────┘  │                     │
│         │          │  ┌─────────┐  │                     │
│         │          │  │Jitter   │  │                     │
│         │          │  │Buffer   │  │                     │
│         │          │  └─────────┘  │                     │
│         │          └───────┬───────┘                     │
│         │          ┌───────┴───────┐                     │
│         │          │ Audio Engine  │                     │
│         │          │   (Oboe)      │                     │
│         │          └───────────────┘                     │
└─────────┼───────────────────────────────────────────────┘
          │ WSS                    ▲ UDP (RTP/RTCP)
          ▼                       ▼
    ┌─────────────────────────────────┐
    │         FreeSWITCH              │
    │   (Verto module + RTP)          │
    └─────────────────────────────────┘
```

## Data Flow

### Outbound Call
1. User dials number in CallActivity
2. MediaSessionManager generates SDP offer with AMR codec
3. VertoClient sends `verto.invite` with SDP over WebSocket
4. FreeSWITCH responds with SDP answer
5. NativeMediaEngine starts: AMR codec + RTP sockets + Oboe audio
6. AdaptiveBitrateController begins monitoring RTCP

### Adaptive Bitrate
1. RTCP Receiver Reports arrive every ~5 seconds
2. Native RTCP handler parses loss/jitter/RTT
3. JNI callback notifies AdaptiveBitrateController
4. Controller computes target AMR mode
5. AMR encoder mode is switched (smooth stepping, max 2 steps)
6. CMR field set in outgoing RTP packets
