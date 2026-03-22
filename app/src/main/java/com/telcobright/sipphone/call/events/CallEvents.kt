package com.telcobright.sipphone.call.events

import com.telcobright.sipphone.statemachine.GenericEvent

/** Verto login succeeded. */
class RegisteredEvent(
    val sessionId: String
) : GenericEvent("REGISTERED", "SIP registration successful")

/** Verto login failed. */
class RegistrationFailedEvent(
    val reason: String
) : GenericEvent("REGISTRATION_FAILED", reason)

/** User initiates outbound call. */
class InviteEvent(
    val destination: String,
    val sdpOffer: String
) : GenericEvent("INVITE", "Outbound call to $destination")

/** Incoming call from remote. */
class IncomingCallEvent(
    val callId: String,
    val callerNumber: String,
    val remoteSdp: String
) : GenericEvent("INCOMING_CALL", "Call from $callerNumber")

/** Remote party is ringing. */
class RingingEvent(
    val callId: String
) : GenericEvent("RINGING", "Remote ringing")

/** Call answered — media should start. */
class AnsweredEvent(
    val callId: String,
    val remoteSdp: String
) : GenericEvent("ANSWERED", "Call answered")

/** Call ended normally. */
class HangupEvent(
    val callId: String,
    val reason: String = "Normal"
) : GenericEvent("HANGUP", reason)

/** Call failed (error, rejection). */
class FailedEvent(
    val reason: String
) : GenericEvent("FAILED", reason)

/** Media update (re-INVITE). */
class MediaUpdateEvent(
    val callId: String,
    val remoteSdp: String
) : GenericEvent("MEDIA_UPDATE", "SDP update")

/** User answers incoming call. */
class AcceptCallEvent(
    val callId: String
) : GenericEvent("ACCEPT_CALL", "User accepts incoming call")

/** User rejects incoming call. */
class RejectCallEvent(
    val callId: String
) : GenericEvent("REJECT_CALL", "User rejects incoming call")

/** Verto connection lost. */
class DisconnectedEvent(
    val reason: String
) : GenericEvent("DISCONNECTED", reason)
