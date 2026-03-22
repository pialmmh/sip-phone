package com.telcobright.sipphone.call

import android.util.Log
import com.telcobright.sipphone.call.events.*
import com.telcobright.sipphone.media.MediaSessionManager
import com.telcobright.sipphone.statemachine.FluentBuilder
import com.telcobright.sipphone.statemachine.GenericStateMachine
import com.telcobright.sipphone.statemachine.TimeoutEvent
import com.telcobright.sipphone.verto.VertoClient
import java.util.concurrent.TimeUnit

/**
 * Call state machine factory — follows EslCallMachineFactory pattern from State-Walk.
 *
 * States:
 * - IDLE:        Pool/ready state
 * - REGISTERING: Connecting to FreeSWITCH Verto
 * - READY:       Registered, waiting for user action or incoming call
 * - TRYING:      Outbound call sent, waiting for progress
 * - RINGING:     Remote party ringing (outbound) or incoming call notification
 * - ANSWERED:    Call connected, media active
 * - COMPLETED:   Call ended normally (terminal)
 * - FAILED:      Error or timeout (terminal)
 */
class CallMachineFactory(
    private val vertoClient: VertoClient,
    private val mediaSession: MediaSessionManager,
    private val onStateChanged: (fromState: String, toState: String) -> Unit
) {
    companion object {
        private const val TAG = "CallMachineFactory"
        private const val TRYING_TIMEOUT_SECONDS = 30L
        private const val RINGING_TIMEOUT_SECONDS = 60L
        private const val MAX_CALL_DURATION_SECONDS = 3600L
    }

    fun createMachine(machineId: String): GenericStateMachine<CallData, CallVolatileContext> {
        val machine = FluentBuilder.create<CallData, CallVolatileContext>(machineId)
            .initialState("IDLE")

            // ==================== IDLE ====================
            .state("IDLE")
            .done()

            // ==================== REGISTERING ====================
            .state("REGISTERING")
                .onEntry { m ->
                    Log.d(TAG, "[${m.id}] Connecting to Verto...")
                    vertoClient.connect()
                }
                .on(RegisteredEvent::class.java).to("READY")
                .on(RegistrationFailedEvent::class.java).to("FAILED")
                .on(DisconnectedEvent::class.java).to("FAILED")
                .timeout(15, TimeUnit.SECONDS, "FAILED")
            .done()

            // ==================== READY ====================
            .state("READY")
                .onEntry { m ->
                    Log.d(TAG, "[${m.id}] Registered and ready")
                }
                .on(InviteEvent::class.java).to("TRYING")
                .on(IncomingCallEvent::class.java).to("RINGING")
                .on(DisconnectedEvent::class.java).to("FAILED")
                .stay(InviteEvent::class.java) { m, event ->
                    val invite = event as InviteEvent
                    val data = m.persistingEntity ?: return@stay
                    data.destination = invite.destination
                    data.localSdp = invite.sdpOffer
                    data.direction = CallData.CallDirection.OUTBOUND
                    data.startTimeMs = System.currentTimeMillis()
                }
            .done()

            // ==================== TRYING ====================
            .state("TRYING")
                .onEntry { m ->
                    val data = m.persistingEntity ?: return@onEntry
                    Log.d(TAG, "[${m.id}] TRYING -> calling ${data.destination}")

                    val sdpOffer = mediaSession.createOffer(
                        preferWideband = data.codec == "AMR-WB",
                        codec = data.codec
                    )
                    data.localSdp = sdpOffer

                    val callId = vertoClient.invite(data.destination, sdpOffer)
                    data.callId = callId
                }
                .on(RingingEvent::class.java).to("RINGING")
                .on(AnsweredEvent::class.java).to("ANSWERED")
                .on(HangupEvent::class.java).to("COMPLETED")
                .on(FailedEvent::class.java).to("FAILED")
                .on(DisconnectedEvent::class.java).to("FAILED")
                .timeout(TRYING_TIMEOUT_SECONDS, TimeUnit.SECONDS, "FAILED")
            .done()

            // ==================== RINGING ====================
            .state("RINGING")
                .onEntry { m ->
                    val data = m.persistingEntity ?: return@onEntry
                    if (data.direction == CallData.CallDirection.INBOUND) {
                        Log.d(TAG, "[${m.id}] Incoming call from ${data.callerNumber}")
                    } else {
                        Log.d(TAG, "[${m.id}] Remote ringing")
                    }
                }
                .on(AnsweredEvent::class.java).to("ANSWERED")
                .on(AcceptCallEvent::class.java).to("ANSWERED")
                .on(RejectCallEvent::class.java).to("COMPLETED")
                .on(HangupEvent::class.java).to("COMPLETED")
                .on(FailedEvent::class.java).to("FAILED")
                .on(DisconnectedEvent::class.java).to("FAILED")
                .timeout(RINGING_TIMEOUT_SECONDS, TimeUnit.SECONDS, "FAILED")
            .done()

            // ==================== ANSWERED ====================
            .state("ANSWERED")
                .onEntry { m ->
                    val data = m.persistingEntity ?: return@onEntry
                    data.answerTimeMs = System.currentTimeMillis()
                    Log.d(TAG, "[${m.id}] Call ANSWERED — starting media")

                    if (data.remoteSdp.isNotEmpty()) {
                        mediaSession.startMedia(data.remoteSdp)
                    }
                }
                .onExit { _ ->
                    mediaSession.stopMedia()
                }
                .on(HangupEvent::class.java).to("COMPLETED")
                .on(FailedEvent::class.java).to("FAILED")
                .on(DisconnectedEvent::class.java).to("FAILED")
                .stay(MediaUpdateEvent::class.java) { m, event ->
                    val update = event as MediaUpdateEvent
                    val data = m.persistingEntity ?: return@stay
                    data.remoteSdp = update.remoteSdp
                    mediaSession.stopMedia()
                    mediaSession.startMedia(update.remoteSdp)
                }
                .timeout(MAX_CALL_DURATION_SECONDS, TimeUnit.SECONDS, "COMPLETED")
            .done()

            // ==================== COMPLETED ====================
            .state("COMPLETED")
                .onEntry { m ->
                    val data = m.persistingEntity ?: return@onEntry
                    data.endTimeMs = System.currentTimeMillis()
                    Log.d(TAG, "[${m.id}] Call COMPLETED: ${data.hangupReason}")
                }
                .finalState()
            .done()

            // ==================== FAILED ====================
            .state("FAILED")
                .onEntry { m ->
                    val data = m.persistingEntity ?: return@onEntry
                    data.endTimeMs = System.currentTimeMillis()
                    Log.e(TAG, "[${m.id}] Call FAILED: ${data.hangupReason}")
                    mediaSession.stopMedia()
                }
                .finalState()
            .done()

            .build()

        machine.onStateTransition = { from, to, _ ->
            onStateChanged(from, to)
        }

        return machine
    }
}
