package com.telcobright.sipphone.verto

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verto WebSocket client for FreeSWITCH signaling.
 *
 * Implements the Verto JSON-RPC protocol over WebSocket:
 * - login (authentication)
 * - verto.invite (outbound call)
 * - verto.answer (accept inbound call)
 * - verto.bye (hang up)
 * - verto.media (SDP exchange)
 */
class VertoClient(
    private val serverUrl: String,
    private val login: String,
    private val password: String,
    private val listener: VertoEventListener
) {

    companion object {
        private const val TAG = "VertoClient"
        private const val PING_INTERVAL_SEC = 30L
    }

    interface VertoEventListener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onLoginSuccess(sessionId: String)
        fun onLoginFailed(error: String)
        fun onIncomingCall(callId: String, callerNumber: String, sdp: String)
        fun onCallAnswered(callId: String, sdp: String)
        fun onCallEnded(callId: String, reason: String)
        fun onMediaUpdate(callId: String, sdp: String)
        fun onError(error: String)
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private val requestId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private var sessionId: String? = null
    private var scope: CoroutineScope? = null

    fun connect() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                listener.onConnected()
                performLogin()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                listener.onDisconnected(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                listener.onDisconnected(t.message ?: "Connection failed")
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        scope?.cancel()
        scope = null
    }

    /**
     * Make an outbound call.
     *
     * @param destination  Phone number or extension
     * @param localSdp     Our SDP offer (with AMR codec)
     * @return Call ID
     */
    fun invite(destination: String, localSdp: String): String {
        val callId = UUID.randomUUID().toString()

        val params = JsonObject().apply {
            addProperty("callID", callId)
            addProperty("sdp", localSdp)
            add("dialogParams", JsonObject().apply {
                addProperty("callID", callId)
                addProperty("destination_number", destination)
                addProperty("caller_id_name", login)
                addProperty("caller_id_number", login)
                addProperty("remote_caller_id_name", destination)
                addProperty("remote_caller_id_number", destination)
            })
        }

        sendRequest("verto.invite", params)
        return callId
    }

    /**
     * Answer an incoming call.
     *
     * @param callId    Call ID from onIncomingCall
     * @param localSdp  Our SDP answer (with AMR codec)
     */
    fun answer(callId: String, localSdp: String) {
        val params = JsonObject().apply {
            addProperty("callID", callId)
            addProperty("sdp", localSdp)
            add("dialogParams", JsonObject().apply {
                addProperty("callID", callId)
            })
        }

        sendRequest("verto.answer", params)
    }

    /**
     * Hang up a call.
     */
    fun bye(callId: String) {
        val params = JsonObject().apply {
            addProperty("callID", callId)
            add("dialogParams", JsonObject().apply {
                addProperty("callID", callId)
            })
        }

        sendRequest("verto.bye", params)
    }

    /**
     * Send DTMF digits.
     */
    fun sendDtmf(callId: String, digits: String) {
        val params = JsonObject().apply {
            addProperty("callID", callId)
            addProperty("dtmf", digits)
            add("dialogParams", JsonObject().apply {
                addProperty("callID", callId)
            })
        }

        sendRequest("verto.info", params)
    }

    private fun performLogin() {
        sessionId = UUID.randomUUID().toString()

        val params = JsonObject().apply {
            addProperty("login", login)
            addProperty("passwd", password)
            addProperty("sessid", sessionId)
        }

        scope?.launch {
            try {
                val response = sendRequestAsync("login", params)
                if (response.has("sessid")) {
                    sessionId = response.get("sessid").asString
                    listener.onLoginSuccess(sessionId!!)
                } else {
                    listener.onLoginFailed("Login failed")
                }
            } catch (e: Exception) {
                listener.onLoginFailed(e.message ?: "Login error")
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject

            /* Check if this is a response to a pending request */
            if (json.has("id")) {
                val id = json.get("id").asInt
                val deferred = pendingRequests.remove(id)
                if (deferred != null) {
                    if (json.has("result")) {
                        deferred.complete(json.getAsJsonObject("result"))
                    } else if (json.has("error")) {
                        deferred.completeExceptionally(
                            Exception(json.getAsJsonObject("error").toString())
                        )
                    }
                    return
                }
            }

            /* This is an inbound event from FreeSWITCH */
            if (json.has("method")) {
                handleEvent(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${e.message}")
        }
    }

    private fun handleEvent(json: JsonObject) {
        val method = json.get("method").asString
        val params = json.getAsJsonObject("params") ?: return
        val eventId = if (json.has("id")) json.get("id").asInt else 0

        when (method) {
            "verto.invite" -> {
                val callId = params.get("callID")?.asString ?: return
                val sdp = params.get("sdp")?.asString ?: ""
                val callerNumber = params.getAsJsonObject("dialogParams")
                    ?.get("caller_id_number")?.asString ?: "Unknown"
                listener.onIncomingCall(callId, callerNumber, sdp)
            }

            "verto.answer" -> {
                val callId = params.get("callID")?.asString ?: return
                val sdp = params.get("sdp")?.asString ?: ""
                listener.onCallAnswered(callId, sdp)
                sendResult(eventId, JsonObject())
            }

            "verto.bye" -> {
                val callId = params.get("callID")?.asString ?: return
                val reason = params.get("cause")?.asString ?: "Normal"
                listener.onCallEnded(callId, reason)
                sendResult(eventId, JsonObject())
            }

            "verto.media" -> {
                val callId = params.get("callID")?.asString ?: return
                val sdp = params.get("sdp")?.asString ?: ""
                listener.onMediaUpdate(callId, sdp)
                sendResult(eventId, JsonObject())
            }

            "verto.display" -> {
                /* Display update — just acknowledge */
                sendResult(eventId, JsonObject())
            }

            else -> {
                Log.d(TAG, "Unhandled event: $method")
            }
        }
    }

    private fun sendRequest(method: String, params: JsonObject) {
        val id = requestId.getAndIncrement()
        val msg = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }

        val text = gson.toJson(msg)
        Log.d(TAG, "Sending: $method (id=$id)")
        webSocket?.send(text)
    }

    private suspend fun sendRequestAsync(method: String, params: JsonObject): JsonObject {
        val id = requestId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred

        val msg = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }

        val text = gson.toJson(msg)
        Log.d(TAG, "Sending async: $method (id=$id)")
        webSocket?.send(text)

        return withTimeout(10_000) { deferred.await() }
    }

    private fun sendResult(id: Int, result: JsonObject) {
        if (id == 0) return
        val msg = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            add("result", result)
        }
        webSocket?.send(gson.toJson(msg))
    }
}
