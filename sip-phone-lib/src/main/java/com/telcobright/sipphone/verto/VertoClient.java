package com.telcobright.sipphone.verto;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verto WebSocket client for FreeSWITCH signaling.
 * Pure Java — no Android dependencies.
 */
public class VertoClient {

    private static final Logger log = LoggerFactory.getLogger(VertoClient.class);

    public interface VertoEventListener {
        void onConnected();
        void onDisconnected(String reason);
        void onLoginSuccess(String sessionId);
        void onLoginFailed(String error);
        void onIncomingCall(String callId, String callerNumber, String sdp);
        void onCallAnswered(String callId, String sdp);
        void onCallEnded(String callId, String reason);
        void onMediaUpdate(String callId, String sdp);
        void onError(String error);
    }

    private final String serverUrl;
    private final String login;
    private final String password;
    private final VertoEventListener listener;

    private final Gson gson = new Gson();
    private final OkHttpClient client;
    private WebSocket webSocket;
    private final AtomicInteger requestId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private String sessionId;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "verto-login");
        t.setDaemon(true);
        return t;
    });

    public VertoClient(String serverUrl, String login, String password, VertoEventListener listener) {
        this.serverUrl = serverUrl;
        this.login = login;
        this.password = password;
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public void connect() {
        Request request = new Request.Builder().url(serverUrl).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                log.info("WebSocket connected to {}", serverUrl);
                listener.onConnected();
                performLogin();
            }

            @Override public void onMessage(WebSocket ws, String text) {
                handleMessage(text);
            }

            @Override public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                log.warn("WebSocket CLOSED — server={}, code={}, reason='{}'", serverUrl, code, reason);
                listener.onDisconnected(reason);
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                String detail = t.getMessage() != null ? t.getMessage() : "Unknown error";
                int httpCode = response != null ? response.code() : -1;
                log.error("WebSocket FAILED — server={}, error='{}', httpCode={}", serverUrl, detail, httpCode);
                listener.onDisconnected(detail);
            }
        });
    }

    public void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "User disconnect");
            webSocket = null;
        }
        executor.shutdownNow();
    }

    public String invite(String destination, String localSdp) {
        String callId = UUID.randomUUID().toString();
        JsonObject params = new JsonObject();
        params.addProperty("callID", callId);
        params.addProperty("sdp", localSdp);
        JsonObject dialogParams = new JsonObject();
        dialogParams.addProperty("callID", callId);
        dialogParams.addProperty("destination_number", destination);
        dialogParams.addProperty("caller_id_name", login);
        dialogParams.addProperty("caller_id_number", login);
        dialogParams.addProperty("remote_caller_id_name", destination);
        dialogParams.addProperty("remote_caller_id_number", destination);
        params.add("dialogParams", dialogParams);
        sendRequest("verto.invite", params);
        return callId;
    }

    public void answer(String callId, String localSdp) {
        JsonObject params = new JsonObject();
        params.addProperty("callID", callId);
        params.addProperty("sdp", localSdp);
        JsonObject dp = new JsonObject();
        dp.addProperty("callID", callId);
        params.add("dialogParams", dp);
        sendRequest("verto.answer", params);
    }

    public void bye(String callId) {
        JsonObject params = new JsonObject();
        params.addProperty("callID", callId);
        JsonObject dp = new JsonObject();
        dp.addProperty("callID", callId);
        params.add("dialogParams", dp);
        sendRequest("verto.bye", params);
    }

    public void sendDtmf(String callId, String digits) {
        JsonObject params = new JsonObject();
        params.addProperty("callID", callId);
        params.addProperty("dtmf", digits);
        JsonObject dp = new JsonObject();
        dp.addProperty("callID", callId);
        params.add("dialogParams", dp);
        sendRequest("verto.info", params);
    }

    private void performLogin() {
        sessionId = UUID.randomUUID().toString();
        JsonObject params = new JsonObject();
        params.addProperty("login", login);
        params.addProperty("passwd", password);
        params.addProperty("sessid", sessionId);

        executor.submit(() -> {
            try {
                log.info("Sending login request for user '{}' to {}", login, serverUrl);
                JsonObject response = sendRequestAsync("login", params).get(10, TimeUnit.SECONDS);
                if (response.has("sessid")) {
                    sessionId = response.get("sessid").getAsString();
                    log.info("Registration SUCCESS — user='{}', sessionId={}", login, sessionId);
                    listener.onLoginSuccess(sessionId);
                } else {
                    log.warn("Registration FAILED — user='{}', response: {}", login, response);
                    listener.onLoginFailed("Login failed — unexpected response");
                }
            } catch (java.util.concurrent.TimeoutException e) {
                log.error("Registration TIMEOUT — user='{}', server={} (no response in 10s)", login, serverUrl);
                listener.onLoginFailed("Login timeout — no response from server");
            } catch (java.util.concurrent.ExecutionException e) {
                String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                log.error("Registration REJECTED — user='{}', server={}, error: {}", login, serverUrl, cause);
                listener.onLoginFailed("Login rejected: " + cause);
            } catch (Exception e) {
                log.error("Registration ERROR — user='{}', server={}: {}", login, serverUrl, e.getMessage(), e);
                listener.onLoginFailed(e.getMessage() != null ? e.getMessage() : "Login error");
            }
        });
    }

    private void handleMessage(String text) {
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();

            if (json.has("id")) {
                int id = json.get("id").getAsInt();
                CompletableFuture<JsonObject> future = pendingRequests.remove(id);
                if (future != null) {
                    if (json.has("result")) {
                        future.complete(json.getAsJsonObject("result"));
                    } else if (json.has("error")) {
                        future.completeExceptionally(new Exception(json.getAsJsonObject("error").toString()));
                    }
                    return;
                }
            }

            if (json.has("method")) {
                handleEvent(json);
            }
        } catch (Exception e) {
            log.error("Failed to parse message: {}", e.getMessage());
        }
    }

    private void handleEvent(JsonObject json) {
        String method = json.get("method").getAsString();
        JsonObject params = json.has("params") ? json.getAsJsonObject("params") : null;
        if (params == null) return;
        int eventId = json.has("id") ? json.get("id").getAsInt() : 0;

        switch (method) {
            case "verto.invite" -> {
                String callId = params.has("callID") ? params.get("callID").getAsString() : null;
                if (callId == null) return;
                String sdp = params.has("sdp") ? params.get("sdp").getAsString() : "";
                String caller = "Unknown";
                if (params.has("dialogParams")) {
                    var dp = params.getAsJsonObject("dialogParams");
                    if (dp.has("caller_id_number")) caller = dp.get("caller_id_number").getAsString();
                }
                listener.onIncomingCall(callId, caller, sdp);
            }
            case "verto.answer" -> {
                String callId = params.has("callID") ? params.get("callID").getAsString() : null;
                if (callId == null) return;
                String sdp = params.has("sdp") ? params.get("sdp").getAsString() : "";
                listener.onCallAnswered(callId, sdp);
                sendResult(eventId, new JsonObject());
            }
            case "verto.bye" -> {
                String callId = params.has("callID") ? params.get("callID").getAsString() : null;
                if (callId == null) return;
                String reason = params.has("cause") ? params.get("cause").getAsString() : "Normal";
                listener.onCallEnded(callId, reason);
                sendResult(eventId, new JsonObject());
            }
            case "verto.media" -> {
                String callId = params.has("callID") ? params.get("callID").getAsString() : null;
                if (callId == null) return;
                String sdp = params.has("sdp") ? params.get("sdp").getAsString() : "";
                listener.onMediaUpdate(callId, sdp);
                sendResult(eventId, new JsonObject());
            }
            case "verto.display" -> sendResult(eventId, new JsonObject());
            default -> log.debug("Unhandled event: {}", method);
        }
    }

    private void sendRequest(String method, JsonObject params) {
        int id = requestId.getAndIncrement();
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("id", id);
        msg.addProperty("method", method);
        msg.add("params", params);
        String text = gson.toJson(msg);
        log.debug("Sending: {} (id={})", method, id);
        if (webSocket != null) webSocket.send(text);
    }

    private CompletableFuture<JsonObject> sendRequestAsync(String method, JsonObject params) {
        int id = requestId.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("id", id);
        msg.addProperty("method", method);
        msg.add("params", params);
        String text = gson.toJson(msg);
        log.debug("Sending async: {} (id={})", method, id);
        if (webSocket != null) webSocket.send(text);
        return future;
    }

    private void sendResult(int id, JsonObject result) {
        if (id == 0) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("id", id);
        msg.add("result", result);
        if (webSocket != null) webSocket.send(gson.toJson(msg));
    }
}
