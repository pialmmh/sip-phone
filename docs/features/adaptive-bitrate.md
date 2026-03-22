# Adaptive Bitrate Control

## Overview
Dynamically adjusts AMR codec bitrate based on network quality metrics from RTCP.

## AMR Mode Ladder

### AMR-NB (8kHz)
| Mode | Bitrate | Use Case |
|------|---------|----------|
| MR122 | 12.2 kbps | Excellent network |
| MR102 | 10.2 kbps | Good network |
| MR795 | 7.95 kbps | Moderate quality |
| MR74 | 7.40 kbps | Some packet loss |
| MR67 | 6.70 kbps | Poor network |
| MR59 | 5.90 kbps | Bad network |
| MR515 | 5.15 kbps | Very bad |
| MR475 | 4.75 kbps | Survival mode |

### AMR-WB (16kHz)
| Mode | Bitrate | Use Case |
|------|---------|----------|
| WB 23.85k | 23.85 kbps | Excellent |
| WB 23.05k | 23.05 kbps | Very good |
| WB 19.85k | 19.85 kbps | Good |
| WB 18.25k | 18.25 kbps | Above average |
| WB 15.85k | 15.85 kbps | Average |
| WB 14.25k | 14.25 kbps | Below average |
| WB 12.65k | 12.65 kbps | Fair |
| WB 8.85k | 8.85 kbps | Poor |
| WB 6.60k | 6.60 kbps | Survival |

## Quality Thresholds
- Loss < 1%, Jitter < 20ms → Best quality
- Loss < 3%, Jitter < 40ms → Good
- Loss < 5%, Jitter < 60ms → Fair
- Loss < 10% → Poor
- Loss ≥ 10% → Survival mode

## Smoothing
Max 2 mode steps per adjustment to prevent audio artifacts from rapid switching.

## CMR (Codec Mode Request)
AMR's built-in mechanism (RFC 4867): 4-bit field in RTP payload header requesting the remote side to also switch modes.
