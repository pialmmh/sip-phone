package com.telcobright.sipphone.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.telcobright.sipphone.R
import com.telcobright.sipphone.call.CallData
import com.telcobright.sipphone.call.CallMachineFactory
import com.telcobright.sipphone.call.CallVolatileContext
import com.telcobright.sipphone.call.events.*
import com.telcobright.sipphone.databinding.ActivityCallBinding
import com.telcobright.sipphone.media.MediaSessionManager
import com.telcobright.sipphone.service.CallService
import com.telcobright.sipphone.statemachine.GenericStateMachine
import com.telcobright.sipphone.statemachine.StateMachineRegistry
import com.telcobright.sipphone.verto.SdpBuilder
import com.telcobright.sipphone.verto.VertoClient
import kotlinx.coroutines.*
import java.util.UUID

class CallActivity : AppCompatActivity(), VertoClient.VertoEventListener {

    private lateinit var binding: ActivityCallBinding
    private var vertoClient: VertoClient? = null
    private val mediaSession = MediaSessionManager()
    private val registry = StateMachineRegistry()

    private var callMachine: GenericStateMachine<CallData, CallVolatileContext>? = null
    private var callMachineFactory: CallMachineFactory? = null

    private var callStartTime: Long = 0
    private var durationJob: Job? = null
    private var isMuted = false
    private var isSpeaker = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Audio permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        setupUi()
        setupMediaCallbacks()
    }

    override fun onDestroy() {
        super.onDestroy()
        hangup()
        vertoClient?.disconnect()
        registry.shutdown()
    }

    private fun checkPermissions() {
        val needed = arrayOf(Manifest.permission.RECORD_AUDIO)
        if (!needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(needed)
        }
    }

    private fun setupUi() {
        binding.btnRegister.setOnClickListener { doRegister() }

        binding.btnCall.setOnClickListener {
            val number = binding.etPhoneNumber.text?.toString()?.trim()
            if (!number.isNullOrEmpty()) {
                makeCall(number)
            }
        }

        binding.btnHangup.setOnClickListener { hangup() }

        binding.btnMute.setOnClickListener {
            isMuted = !isMuted
            mediaSession.setMuted(isMuted)
            binding.btnMute.text = if (isMuted) "Unmute" else "Mute"
        }

        binding.btnSpeaker.setOnClickListener {
            isSpeaker = !isSpeaker
            val am = getSystemService(AudioManager::class.java)
            am.isSpeakerphoneOn = isSpeaker
            binding.btnSpeaker.text = if (isSpeaker) "Earpiece" else "Speaker"
        }

        /* Default PCMU selected */
        binding.chipPcmu.isChecked = true
    }

    private fun setupMediaCallbacks() {
        mediaSession.onModeChanged = { _, label ->
            runOnUiThread { binding.tvCodec.text = label }
        }
        mediaSession.onQualityChanged = { loss, jitter, _ ->
            runOnUiThread {
                binding.tvPacketLoss.text = "Loss: %.1f%%".format(loss)
                binding.tvJitter.text = "Jitter: %.0fms".format(jitter)
                binding.statsPanel.visibility = View.VISIBLE
            }
        }
    }

    private fun getSelectedCodec(): String {
        return when {
            binding.chipAmrWb.isChecked -> SdpBuilder.CODEC_AMR_WB
            binding.chipAmrNb.isChecked -> SdpBuilder.CODEC_AMR_NB
            else -> SdpBuilder.CODEC_PCMU
        }
    }

    // ==================== REGISTRATION ====================

    private fun doRegister() {
        val server = binding.etServerUrl.text?.toString()?.trim() ?: return
        val user = binding.etUsername.text?.toString()?.trim() ?: return
        val pass = binding.etPassword.text?.toString()?.trim() ?: return

        if (server.isEmpty() || user.isEmpty()) {
            Toast.makeText(this, "Fill server and username", Toast.LENGTH_SHORT).show()
            return
        }

        vertoClient?.disconnect()
        vertoClient = VertoClient(server, user, pass, this)

        callMachineFactory = CallMachineFactory(vertoClient!!, mediaSession) { from, to ->
            runOnUiThread { onCallStateChanged(from, to) }
        }

        updateStatus("Connecting...", R.color.primary)
        binding.btnRegister.isEnabled = false
        vertoClient?.connect()
    }

    // ==================== CALL ====================

    private fun makeCall(destination: String) {
        val machineId = UUID.randomUUID().toString()
        val machine = callMachineFactory?.createMachine(machineId) ?: return

        val callData = CallData(
            destination = destination,
            codec = getSelectedCodec(),
            direction = CallData.CallDirection.OUTBOUND
        )

        machine.persistingEntity = callData
        machine.context = CallVolatileContext(
            serverUrl = binding.etServerUrl.text.toString(),
            username = binding.etUsername.text.toString(),
            password = binding.etPassword.text.toString()
        )

        callMachine = machine
        registry.register(machineId, machine)
        machine.start()

        /* Trigger TRYING from IDLE → need to transition through */
        machine.transitionTo("TRYING")
    }

    private fun hangup() {
        val machine = callMachine ?: return
        val data = machine.persistingEntity ?: return

        if (data.callId.isNotEmpty()) {
            vertoClient?.bye(data.callId)
        }

        data.hangupReason = "User hangup"
        machine.fire(HangupEvent(data.callId, "User hangup"))
        callMachine = null
    }

    // ==================== STATE CHANGE UI ====================

    private fun onCallStateChanged(fromState: String, toState: String) {
        binding.tvCallState.text = toState

        when (toState) {
            "IDLE", "READY" -> {
                binding.btnCall.visibility = View.VISIBLE
                binding.btnHangup.visibility = View.GONE
                binding.inCallControls.visibility = View.GONE
                binding.cardCall.alpha = 1f
                setFieldsEnabled(true)
                durationJob?.cancel()
                binding.tvCallDuration.text = ""
                stopCallService()
            }
            "TRYING" -> {
                binding.btnCall.visibility = View.GONE
                binding.btnHangup.visibility = View.VISIBLE
                binding.tvCallDuration.text = "Calling..."
                binding.inCallControls.visibility = View.VISIBLE
                setFieldsEnabled(false)
            }
            "RINGING" -> {
                binding.tvCallDuration.text = "Ringing..."
            }
            "ANSWERED" -> {
                binding.tvCallDuration.text = "00:00"
                binding.inCallControls.visibility = View.VISIBLE
                startCallTimer()
                startCallService()
            }
            "COMPLETED", "FAILED" -> {
                binding.btnCall.visibility = View.VISIBLE
                binding.btnHangup.visibility = View.GONE
                binding.inCallControls.visibility = View.GONE
                setFieldsEnabled(true)
                durationJob?.cancel()
                stopCallService()
                callMachine = null

                val reason = callMachine?.persistingEntity?.hangupReason ?: toState
                Toast.makeText(this, "Call ended: $reason", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        binding.etPhoneNumber.isEnabled = enabled
        binding.chipPcmu.isEnabled = enabled
        binding.chipAmrNb.isEnabled = enabled
        binding.chipAmrWb.isEnabled = enabled
    }

    private fun startCallTimer() {
        callStartTime = SystemClock.elapsedRealtime()
        durationJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = (SystemClock.elapsedRealtime() - callStartTime) / 1000
                binding.tvCallDuration.text = "%02d:%02d".format(elapsed / 60, elapsed % 60)
                delay(1000)
            }
        }
    }

    private fun updateStatus(text: String, colorRes: Int) {
        binding.tvStatus.text = text
        val dot = binding.statusDot.background as? GradientDrawable
        dot?.setColor(ContextCompat.getColor(this, colorRes))
    }

    private fun startCallService() {
        startForegroundService(Intent(this, CallService::class.java))
    }

    private fun stopCallService() {
        stopService(Intent(this, CallService::class.java))
    }

    // ==================== VERTO CALLBACKS ====================

    override fun onConnected() {
        runOnUiThread {
            updateStatus("Connected, logging in...", R.color.primary)
        }
    }

    override fun onDisconnected(reason: String) {
        runOnUiThread {
            updateStatus("Disconnected: $reason", R.color.call_red)
            binding.btnRegister.isEnabled = true

            callMachine?.fire(DisconnectedEvent(reason))
        }
    }

    override fun onLoginSuccess(sessionId: String) {
        runOnUiThread {
            updateStatus("Registered", R.color.call_green)
            binding.btnRegister.text = "Re-register"
            binding.btnRegister.isEnabled = true
        }
    }

    override fun onLoginFailed(error: String) {
        runOnUiThread {
            updateStatus("Login failed: $error", R.color.call_red)
            binding.btnRegister.isEnabled = true
        }
    }

    override fun onIncomingCall(callId: String, callerNumber: String, sdp: String) {
        runOnUiThread {
            /* Create machine for incoming call */
            val machineId = callId
            val machine = callMachineFactory?.createMachine(machineId) ?: return@runOnUiThread

            val callData = CallData(
                callId = callId,
                callerNumber = callerNumber,
                codec = getSelectedCodec(),
                remoteSdp = sdp,
                direction = CallData.CallDirection.INBOUND
            )
            machine.persistingEntity = callData
            callMachine = machine
            registry.register(machineId, machine)
            machine.start()

            /* Auto-answer for now */
            val answerSdp = mediaSession.createAnswer(sdp, getSelectedCodec())
            callData.localSdp = answerSdp
            vertoClient?.answer(callId, answerSdp)

            callData.remoteSdp = sdp
            machine.fire(AnsweredEvent(callId, sdp))
        }
    }

    override fun onCallAnswered(callId: String, sdp: String) {
        runOnUiThread {
            callMachine?.let { m ->
                m.persistingEntity?.remoteSdp = sdp
                m.fire(AnsweredEvent(callId, sdp))
            }
        }
    }

    override fun onCallEnded(callId: String, reason: String) {
        runOnUiThread {
            callMachine?.let { m ->
                m.persistingEntity?.hangupReason = reason
                m.fire(HangupEvent(callId, reason))
            }
        }
    }

    override fun onMediaUpdate(callId: String, sdp: String) {
        runOnUiThread {
            callMachine?.fire(MediaUpdateEvent(callId, sdp))
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
        }
    }
}
