package com.telcobright.sipphone.verto;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds SDP offers/answers with AMR and PCMU codecs for Verto/FreeSWITCH.
 */
public final class SdpBuilder {

    public static final String CODEC_PCMU = "PCMU";
    public static final String CODEC_AMR_NB = "AMR-NB";
    public static final String CODEC_AMR_WB = "AMR-WB";

    public static final int CODEC_TYPE_NB = 0;
    public static final int CODEC_TYPE_WB = 1;

    private static final int PT_PCMU = 0;
    private static final int PT_AMR_DYNAMIC = 96;

    private SdpBuilder() {}

    public static String buildOffer(String localIp, int rtpPort, String codec) {
        return switch (codec) {
            case CODEC_AMR_NB -> buildAmrOffer(localIp, rtpPort, CODEC_TYPE_NB);
            case CODEC_AMR_WB -> buildAmrOffer(localIp, rtpPort, CODEC_TYPE_WB);
            default -> buildPcmuOffer(localIp, rtpPort);
        };
    }

    private static String buildPcmuOffer(String localIp, int rtpPort) {
        return "v=0\r\n" +
                "o=- " + System.currentTimeMillis() + " 1 IN IP4 " + localIp + "\r\n" +
                "s=SipPhone\r\n" +
                "c=IN IP4 " + localIp + "\r\n" +
                "t=0 0\r\n" +
                "m=audio " + rtpPort + " RTP/AVP " + PT_PCMU + " 101\r\n" +
                "a=rtpmap:" + PT_PCMU + " PCMU/8000\r\n" +
                "a=ptime:20\r\n" +
                "a=sendrecv\r\n" +
                "a=rtpmap:101 telephone-event/8000\r\n" +
                "a=fmtp:101 0-15";
    }

    private static String buildAmrOffer(String localIp, int rtpPort, int codecType) {
        int pt = PT_AMR_DYNAMIC;
        String codecName = codecType == CODEC_TYPE_WB ? "AMR-WB" : "AMR";
        int sampleRate = codecType == CODEC_TYPE_WB ? 16000 : 8000;

        return "v=0\r\n" +
                "o=- " + System.currentTimeMillis() + " 1 IN IP4 " + localIp + "\r\n" +
                "s=SipPhone\r\n" +
                "c=IN IP4 " + localIp + "\r\n" +
                "t=0 0\r\n" +
                "m=audio " + rtpPort + " RTP/AVP " + pt + " " + (pt + 1) + "\r\n" +
                "a=rtpmap:" + pt + " " + codecName + "/" + sampleRate + "\r\n" +
                "a=fmtp:" + pt + " octet-align=1; mode-change-capability=2; max-red=0\r\n" +
                "a=ptime:20\r\n" +
                "a=sendrecv\r\n" +
                "a=rtpmap:" + (pt + 1) + " telephone-event/8000\r\n" +
                "a=fmtp:" + (pt + 1) + " 0-15";
    }

    public static String buildAnswer(String localIp, int rtpPort, String remoteSdp, String preferredCodec) {
        SdpMediaInfo parsed = parseRemoteSdp(remoteSdp);
        if (parsed != null) {
            return buildOffer(localIp, rtpPort, parsed.codecName());
        }
        return buildOffer(localIp, rtpPort, preferredCodec);
    }

    public static SdpMediaInfo parseRemoteSdp(String sdp) {
        Matcher connMatch = Pattern.compile("c=IN IP4 (\\S+)").matcher(sdp);
        Matcher mediaMatch = Pattern.compile("m=audio (\\d+) ").matcher(sdp);
        if (!connMatch.find() || !mediaMatch.find()) return null;

        String remoteIp = connMatch.group(1);
        int remotePort = Integer.parseInt(mediaMatch.group(1));

        Matcher amrWb = Pattern.compile("a=rtpmap:(\\d+)\\s+AMR-WB/").matcher(sdp);
        Matcher amrNb = Pattern.compile("a=rtpmap:(\\d+)\\s+AMR/").matcher(sdp);
        Matcher pcmu = Pattern.compile("a=rtpmap:(\\d+)\\s+PCMU/").matcher(sdp);

        if (amrWb.find()) {
            return new SdpMediaInfo(remoteIp, remotePort, remotePort + 1,
                    Integer.parseInt(amrWb.group(1)), CODEC_TYPE_WB, CODEC_AMR_WB);
        } else if (amrNb.find()) {
            return new SdpMediaInfo(remoteIp, remotePort, remotePort + 1,
                    Integer.parseInt(amrNb.group(1)), CODEC_TYPE_NB, CODEC_AMR_NB);
        } else if (pcmu.find()) {
            return new SdpMediaInfo(remoteIp, remotePort, remotePort + 1,
                    Integer.parseInt(pcmu.group(1)), -1, CODEC_PCMU);
        } else {
            // Check for static PT 0 in m= line
            if (sdp.contains("m=audio " + remotePort + " RTP/AVP 0")) {
                return new SdpMediaInfo(remoteIp, remotePort, remotePort + 1, 0, -1, CODEC_PCMU);
            }
        }
        return null;
    }

    public record SdpMediaInfo(
            String remoteIp, int remoteRtpPort, int remoteRtcpPort,
            int payloadType, int codecType, String codecName) {}
}
