# FreeSWITCH Configuration for AMR + Verto

## Overview
Configuration needed on FreeSWITCH to support AMR codec with Verto signaling.

## Enable AMR Codec

### vars.xml
```xml
<X-PRE-PROCESS cmd="set" data="global_codec_prefs=AMR-WB,AMR,PCMU,PCMA"/>
<X-PRE-PROCESS cmd="set" data="outbound_codec_prefs=AMR-WB,AMR"/>
```

### Verto Configuration (verto.conf.xml)
```xml
<settings>
  <param name="debug" value="0"/>
</settings>
<profiles>
  <profile name="default-v4">
    <param name="bind-local" value="0.0.0.0:8082"/>
    <param name="force-register-domain" value="$${local_ip_v4}"/>
    <param name="secure-combined" value="/etc/freeswitch/tls/wss.pem"/>
    <param name="secure-chain" value="/etc/freeswitch/tls/wss.pem"/>
    <param name="apply-candidate-acl" value="wan_v4.auto"/>
    <param name="rtp-ip" value="$${local_ip_v4}"/>
  </profile>
</profiles>
```

## SDP Handling
FreeSWITCH will negotiate AMR from the SDP. Key fmtp parameters:
- `octet-align=1` — octet-aligned mode (required by our implementation)
- `mode-change-capability=2` — supports mode change every frame
