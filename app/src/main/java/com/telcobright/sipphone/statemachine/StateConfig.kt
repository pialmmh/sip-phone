package com.telcobright.sipphone.statemachine

import java.util.concurrent.TimeUnit

/**
 * State configuration — matches State-Walk's EnhancedStateConfig.
 */
class StateConfig(
    val stateId: String
) {
    var isFinal: Boolean = false
    var entryAction: ((GenericStateMachine<*, *>) -> Unit)? = null
    var exitAction: ((GenericStateMachine<*, *>) -> Unit)? = null
    var timeoutDuration: Long = 0
    var timeoutUnit: TimeUnit = TimeUnit.SECONDS
    var timeoutTarget: String? = null

    val hasTimeout: Boolean get() = timeoutDuration > 0 && timeoutTarget != null

    val timeoutMs: Long
        get() = if (hasTimeout) timeoutUnit.toMillis(timeoutDuration) else 0
}
