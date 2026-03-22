package com.telcobright.sipphone.call

/**
 * Call context — persistent data for a single call leg.
 */
data class CallData(
    var callId: String = "",
    var destination: String = "",
    var callerNumber: String = "",
    var codec: String = "AMR-WB",       // AMR-NB, AMR-WB, PCMU
    var localSdp: String = "",
    var remoteSdp: String = "",
    var direction: CallDirection = CallDirection.OUTBOUND,
    var startTimeMs: Long = 0,
    var answerTimeMs: Long = 0,
    var endTimeMs: Long = 0,
    var hangupReason: String = ""
) {
    enum class CallDirection { INBOUND, OUTBOUND }
}

/**
 * Volatile call context — runtime-only data not persisted.
 */
data class CallVolatileContext(
    var serverUrl: String = "",
    var username: String = "",
    var password: String = ""
)
