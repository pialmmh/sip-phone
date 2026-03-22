package com.telcobright.sipphone.statemachine

/**
 * State machine event interface — matches State-Walk's StateMachineEvent.
 */
interface StateMachineEvent {
    val eventType: String
    val description: String
    val payload: Any?
    val timestamp: Long
}

/**
 * Base implementation for simple events.
 */
open class GenericEvent(
    override val eventType: String,
    override val description: String = "",
    override val payload: Any? = null,
    override val timestamp: Long = System.currentTimeMillis()
) : StateMachineEvent

/**
 * Timeout event — fired when a state's timeout expires.
 */
class TimeoutEvent(
    val sourceState: String,
    val targetState: String
) : GenericEvent(
    eventType = "TIMEOUT",
    description = "Timeout in state $sourceState -> $targetState"
)
