package com.telcobright.sipphone.verto

import com.telcobright.sipphone.media.NativeMediaEngine

/**
 * Builds SDP offers/answers with AMR and PCMU codecs for Verto/FreeSWITCH.
 */
object SdpBuilder {

    const val CODEC_PCMU = "PCMU"
    const val CODEC_AMR_NB = "AMR-NB"
    const val CODEC_AMR_WB = "AMR-WB"

    /* PCMU is payload type 0 (static), AMR uses dynamic 96+ */
    private const val PT_PCMU = 0
    private const val PT_AMR_DYNAMIC = 96

    /**
     * Build an SDP offer for the selected codec.
     */
    fun buildOffer(
        localIp: String,
        rtpPort: Int,
        codec: String = CODEC_PCMU
    ): String {
        return when (codec) {
            CODEC_PCMU -> buildPcmuOffer(localIp, rtpPort)
            CODEC_AMR_NB -> buildAmrOffer(localIp, rtpPort, NativeMediaEngine.CODEC_TYPE_NB)
            CODEC_AMR_WB -> buildAmrOffer(localIp, rtpPort, NativeMediaEngine.CODEC_TYPE_WB)
            else -> buildPcmuOffer(localIp, rtpPort)
        }
    }

    private fun buildPcmuOffer(localIp: String, rtpPort: Int): String {
        return buildString {
            appendLine("v=0")
            appendLine("o=- ${System.currentTimeMillis()} 1 IN IP4 $localIp")
            appendLine("s=SipPhone")
            appendLine("c=IN IP4 $localIp")
            appendLine("t=0 0")
            appendLine("m=audio $rtpPort RTP/AVP $PT_PCMU 101")
            appendLine("a=rtpmap:$PT_PCMU PCMU/8000")
            appendLine("a=ptime:20")
            appendLine("a=sendrecv")
            appendLine("a=rtpmap:101 telephone-event/8000")
            appendLine("a=fmtp:101 0-15")
        }.trimEnd()
    }

    private fun buildAmrOffer(localIp: String, rtpPort: Int, codecType: Int): String {
        val pt = PT_AMR_DYNAMIC
        val codecName = if (codecType == NativeMediaEngine.CODEC_TYPE_WB) "AMR-WB" else "AMR"
        val sampleRate = if (codecType == NativeMediaEngine.CODEC_TYPE_WB) 16000 else 8000

        return buildString {
            appendLine("v=0")
            appendLine("o=- ${System.currentTimeMillis()} 1 IN IP4 $localIp")
            appendLine("s=SipPhone")
            appendLine("c=IN IP4 $localIp")
            appendLine("t=0 0")
            appendLine("m=audio $rtpPort RTP/AVP $pt ${pt + 1}")
            appendLine("a=rtpmap:$pt $codecName/$sampleRate")
            appendLine("a=fmtp:$pt octet-align=1; mode-change-capability=2; max-red=0")
            appendLine("a=ptime:20")
            appendLine("a=sendrecv")
            appendLine("a=rtpmap:${pt + 1} telephone-event/8000")
            appendLine("a=fmtp:${pt + 1} 0-15")
        }.trimEnd()
    }

    /**
     * Build SDP answer matching remote codec preference.
     */
    fun buildAnswer(
        localIp: String,
        rtpPort: Int,
        remoteSdp: String,
        preferredCodec: String = CODEC_PCMU
    ): String {
        val parsed = parseRemoteSdp(remoteSdp)
        return if (parsed != null) {
            buildOffer(localIp, rtpPort, parsed.codecName)
        } else {
            buildOffer(localIp, rtpPort, preferredCodec)
        }
    }

    /**
     * Parse remote SDP to extract media info.
     */
    fun parseRemoteSdp(sdp: String): SdpMediaInfo? {
        val connectionMatch = Regex("""c=IN IP4 (\S+)""").find(sdp) ?: return null
        val mediaMatch = Regex("""m=audio (\d+) """).find(sdp) ?: return null

        val pcmuMatch = Regex("""a=rtpmap:(\d+)\s+PCMU/""").find(sdp)
        val amrWbMatch = Regex("""a=rtpmap:(\d+)\s+AMR-WB/""").find(sdp)
        val amrNbMatch = Regex("""a=rtpmap:(\d+)\s+AMR/""").find(sdp)

        val (payloadType, codecType, codecName) = when {
            amrWbMatch != null -> Triple(
                amrWbMatch.groupValues[1].toInt(),
                NativeMediaEngine.CODEC_TYPE_WB,
                CODEC_AMR_WB
            )
            amrNbMatch != null -> Triple(
                amrNbMatch.groupValues[1].toInt(),
                NativeMediaEngine.CODEC_TYPE_NB,
                CODEC_AMR_NB
            )
            pcmuMatch != null -> Triple(
                pcmuMatch.groupValues[1].toInt(),
                -1, // PCMU handled differently
                CODEC_PCMU
            )
            else -> {
                /* Check if m= line contains 0 (PCMU static PT) */
                val mLine = mediaMatch.value
                if (mLine.contains(" 0 ") || mLine.endsWith(" 0")) {
                    Triple(0, -1, CODEC_PCMU)
                } else {
                    return null
                }
            }
        }

        return SdpMediaInfo(
            remoteIp = connectionMatch.groupValues[1],
            remoteRtpPort = mediaMatch.groupValues[1].toInt(),
            remoteRtcpPort = mediaMatch.groupValues[1].toInt() + 1,
            payloadType = payloadType,
            codecType = codecType,
            codecName = codecName
        )
    }

    data class SdpMediaInfo(
        val remoteIp: String,
        val remoteRtpPort: Int,
        val remoteRtcpPort: Int,
        val payloadType: Int,
        val codecType: Int,
        val codecName: String
    )
}
