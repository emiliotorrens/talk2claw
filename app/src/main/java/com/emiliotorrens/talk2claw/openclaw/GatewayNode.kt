package com.emiliotorrens.talk2claw.openclaw

import android.util.Log
import com.emiliotorrens.talk2claw.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * WebSocket client that connects to the OpenClaw gateway as an official node.
 *
 * Protocol flow:
 *  1. Gateway → {type:"event", event:"connect.challenge", payload:{nonce, ts}}
 *  2. Client → {type:"req", id, method:"connect", params:{...auth...}}
 *  3. Gateway → {type:"res", id, ok:true, payload:{type:"hello-ok", protocol:3, ...}}
 *  4. Exchange RPC calls (chat.send, chat.history, ...)
 */
class GatewayNode(private var settings: AppSettings, private var deviceIdentity: DeviceIdentity? = null) {

    companion object {
        private const val TAG = "GatewayNode"
        internal const val RPC_TIMEOUT_MS = 30_000L
        internal const val HANDSHAKE_TIMEOUT_MS = 10_000L
        internal const val RECONNECT_DELAY_BASE_MS = 3_000L
        internal const val RECONNECT_DELAY_MAX_MS = 60_000L

        // ── Protocol helpers (internal for unit testing) ─────────────────

        internal const val CLIENT_ID = "cli"
        internal const val CLIENT_MODE = "node"
        internal const val ROLE = "operator"

        /** Build the params object for the 'connect' handshake request. */
        internal fun buildConnectParams(
            token: String,
            deviceBlock: JSONObject? = null,
        ): JSONObject = JSONObject().apply {
            put("minProtocol", 3)
            put("maxProtocol", 3)
            put("client", JSONObject().apply {
                put("id", CLIENT_ID)
                put("version", "1.0.0")
                put("platform", "android")
                put("mode", CLIENT_MODE)
            })
            put("role", ROLE)
            put("scopes", JSONArray().apply {
                put("operator.read")
                put("operator.write")
                put("operator.talk.secrets")
            })
            put("caps", JSONArray().apply { put("voice") })
            put("commands", JSONArray())
            put("permissions", JSONObject())
            put("auth", JSONObject().apply {
                put("token", token)
            })
            put("locale", "es-ES")
            put("userAgent", "talk2claw/1.0.0")
            if (deviceBlock != null) put("device", deviceBlock)
        }

        /** Build a generic JSON-RPC request frame. */
        internal fun buildRpcFrame(
            id: String,
            method: String,
            params: JSONObject,
        ): JSONObject = JSONObject().apply {
            put("type", "req")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        /**
         * Extract the assistant reply text from a chat.send RPC response.
         * Tries several field names for forward compatibility.
         */
        internal fun extractChatText(response: JSONObject): String {
            val payload = response.optJSONObject("payload") ?: response
            return payload.optString("message", "")
                .ifEmpty { payload.optString("content", "") }
                .ifEmpty { payload.optString("text", "") }
                .ifEmpty { payload.optString("response", "") }
        }

        /** Extract an error message from a failed RPC response. */
        internal fun extractRpcError(json: JSONObject): String =
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("error", "RPC error")
    }

    // ── Connection state ────────────────────────────────────────

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val isConnected: Boolean get() = _connectionState.value is ConnectionState.Connected

    // ── Internals ──────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)      // No read timeout — WS is long-lived
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)         // Built-in WebSocket keepalive
        .build()

    private var webSocket: WebSocket? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    /** Pending chat responses keyed by runId — completed when final chat event arrives. */
    private val pendingChatRuns = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = RECONNECT_DELAY_BASE_MS
    private var isUserDisconnected = false
    private var connectedNodeId: String? = null
    private var challengeNonce: String? = null

    // ── Configuration ──────────────────────────────────────────

    val isConfigured: Boolean
        get() = settings.gatewayHost.isNotBlank() && settings.gatewayToken.isNotBlank()

    private val wsUrl: String
        get() {
            val host = settings.gatewayHost.trim()
            val withScheme = when {
                host.startsWith("wss://") || host.startsWith("ws://") -> host
                host.startsWith("https://") -> host.replace("https://", "wss://")
                host.startsWith("http://") -> host.replace("http://", "ws://")
                else -> "ws://$host"
            }
            return "$withScheme:${settings.gatewayPort}/"
        }

    // ── Public API ─────────────────────────────────────────────

    /** Connect to the gateway. Safe to call multiple times. */
    fun connect() {
        if (!isConfigured) {
            _connectionState.value = ConnectionState.Error("Gateway not configured")
            return
        }
        isUserDisconnected = false
        reconnectDelayMs = RECONNECT_DELAY_BASE_MS
        openWebSocket()
    }

    /** Disconnect and stop auto-reconnect. */
    fun disconnect() {
        isUserDisconnected = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        connectedNodeId = null
        cancelAllPending("Disconnected by user")
        _connectionState.value = ConnectionState.Disconnected
    }

    /** Apply new settings and reconnect. */
    fun updateSettings(newSettings: AppSettings) {
        settings = newSettings
        if (!isUserDisconnected) {
            webSocket?.close(1001, "Settings changed")
            webSocket = null
            isUserDisconnected = false
            connect()
        }
    }

    /**
     * Send a user message and get the assistant's text response.
     * Uses the chat.send RPC method. The response comes via streaming events,
     * not the RPC response itself (which is just an ACK with runId).
     */
    suspend fun sendChatMessage(message: String): Result<String> {
        if (!isConnected) {
            return Result.failure(Exception("Not connected to gateway"))
        }
        return try {
            val response = rpc("chat.send", JSONObject().apply {
                put("message", message)
                put("sessionKey", "main")
                put("idempotencyKey", UUID.randomUUID().toString())
            })
            // Extract runId from ACK and wait for the chat event
            val payload = response.optJSONObject("payload") ?: response
            val runId = payload.optString("runId", "")
            if (runId.isEmpty()) {
                // Fallback: try to get text directly (older gateway versions)
                val text = extractChatText(response)
                if (text.isNotEmpty()) return Result.success(text)
                return Result.failure(Exception("No runId in chat.send response"))
            }

            Log.d(TAG, "chat.send ACK — waiting for response (runId=$runId)")
            val chatDeferred = CompletableDeferred<String>()
            pendingChatRuns[runId] = chatDeferred

            try {
                val text = withTimeout(RPC_TIMEOUT_MS) { chatDeferred.await() }
                Result.success(text)
            } finally {
                pendingChatRuns.remove(runId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "chat.send error: ${e.message}")
            Result.failure(e)
        }
    }

    /** Get recent conversation history. Returns list of (role, content) pairs. */
    suspend fun getChatHistory(): Result<List<Pair<String, String>>> {
        if (!isConnected) return Result.failure(Exception("Not connected"))
        return try {
            val response = rpc("chat.history", JSONObject().apply {
                put("sessionKey", "main")
            })
            val payload = response.optJSONObject("payload") ?: return Result.success(emptyList())
            val messages = payload.optJSONArray("messages") ?: return Result.success(emptyList())
            val history = mutableListOf<Pair<String, String>>()
            for (i in 0 until messages.length()) {
                val msg = messages.optJSONObject(i) ?: continue
                val role = msg.optString("role", "")
                val content = msg.optString("content", "")
                if (role.isNotEmpty() && content.isNotEmpty()) history.add(role to content)
            }
            Result.success(history)
        } catch (e: Exception) {
            Log.e(TAG, "chat.history error: ${e.message}")
            Result.failure(e)
        }
    }

    /** Cancel all pending RPC calls — e.g. when view is cleared. */
    fun cancelPendingCalls() = cancelAllPending("Cancelled")

    /** Call when the component is destroyed. */
    fun destroy() {
        scope.cancel()
        webSocket?.close(1000, "Destroyed")
        webSocket = null
    }

    // ── WebSocket management ───────────────────────────────────

    private fun openWebSocket() {
        val currentState = _connectionState.value
        if (currentState is ConnectionState.Connecting || currentState is ConnectionState.Connected) {
            return
        }
        val url = wsUrl
        Log.i(TAG, "Connecting to $url")
        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, makeListener())
    }

    private fun makeListener(): WebSocketListener = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "WS opened — awaiting challenge from gateway")
            // Don't set Connected yet — wait for hello-ok
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.v(TAG, "← $text")
            try {
                handleMessage(JSONObject(text))
            } catch (e: Exception) {
                Log.e(TAG, "Parse error: ${e.message} | text=$text")
            }
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closing: $code $reason")
            ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closed: $code $reason")
            onDisconnect("Closed: $reason")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure: ${t.message}")
            onDisconnect(t.message ?: "Connection failure")
        }
    }

    // ── Message routing ────────────────────────────────────────

    private fun handleMessage(json: JSONObject) {
        when (json.optString("type")) {
            "event" -> handleEvent(json)
            "res"   -> handleResponse(json)
            else    -> Log.w(TAG, "Unknown frame type: ${json.optString("type")}")
        }
    }

    private fun handleEvent(json: JSONObject) {
        val event = json.optString("event")
        val payload = json.optJSONObject("payload")
        Log.d(TAG, "Event: $event")
        when (event) {
            "connect.challenge" -> {
                challengeNonce = payload?.optString("nonce")
                Log.d(TAG, "Got challenge (nonce=${challengeNonce?.take(8)}...) — sending connect request")
                sendConnectRequest()
            }
            "chat" -> handleChatEvent(json)
            "disconnect" -> {
                val reason = payload?.optString("reason", "Server disconnect") ?: "Server disconnect"
                Log.w(TAG, "Server disconnected: $reason")
                onDisconnect(reason)
            }
            else -> Log.d(TAG, "Unhandled event: $event")
        }
    }

    /** Handle streaming chat events — extract final assistant text and complete pending runs. */
    private fun handleChatEvent(json: JSONObject) {
        val payload = json.optJSONObject("payload") ?: json
        val runId = payload.optString("runId", json.optString("runId", ""))
        val state = payload.optString("state", "")
        Log.d(TAG, "Chat event: runId=${runId.take(8)} state=$state")
        if (state != "final" || runId.isEmpty()) return

        val messageObj = payload.optJSONObject("message") ?: return

        // Extract text from content array or direct string
        val text = extractTextFromMessage(messageObj)
        if (text.isNotEmpty()) {
            Log.d(TAG, "Chat final (runId=${runId.take(8)}): ${text.take(100)}")
            pendingChatRuns[runId]?.complete(text)
        }
    }

    /** Extract plain text from a chat message object (handles content array format). */
    private fun extractTextFromMessage(message: JSONObject): String {
        // Try content array format: {content: [{type:"text", text:"..."}]}
        val content = message.optJSONArray("content")
        if (content != null) {
            val parts = mutableListOf<String>()
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                if (item.optString("type") == "text") {
                    parts.add(item.optString("text", ""))
                }
            }
            if (parts.isNotEmpty()) return parts.joinToString("\n")
        }
        // Fallback to direct string fields
        return message.optString("content", "")
            .ifEmpty { message.optString("text", "") }
            .ifEmpty { message.optString("message", "") }
    }

    private fun handleResponse(json: JSONObject) {
        val id = json.optString("id")
        val ok = json.optBoolean("ok", false)
        Log.d(TAG, "Response: id=$id ok=$ok")

        val deferred = pending.remove(id) ?: run {
            Log.d(TAG, "No pending request for id=$id (may have timed out)")
            return
        }

        if (ok) {
            deferred.complete(json)
        } else {
            deferred.completeExceptionally(Exception(extractRpcError(json)))
        }
    }

    // ── Handshake ──────────────────────────────────────────────

    private fun sendConnectRequest() {
        val id = generateId()
        val nonce = challengeNonce
        val deviceBlock = if (nonce != null && deviceIdentity != null) {
            deviceIdentity!!.buildDeviceBlock(
                nonce = nonce,
                clientId = CLIENT_ID,
                clientMode = CLIENT_MODE,
                role = ROLE,
                scopes = "operator.read,operator.write,operator.talk.secrets",
                token = settings.gatewayToken,
            )
        } else null

        val params = buildConnectParams(settings.gatewayToken, deviceBlock)
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred

        val frame = buildRpcFrame(id, "connect", params)
        if (!sendFrame(frame.toString())) {
            pending.remove(id)
            onDisconnect("Failed to send connect frame")
            return
        }

        scope.launch {
            try {
                val response = withTimeout(HANDSHAKE_TIMEOUT_MS) { deferred.await() }
                onConnectResponse(response)
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Handshake timed out")
                onDisconnect("Handshake timeout")
            } catch (e: Exception) {
                Log.e(TAG, "Handshake failed: ${e.message}")
                onDisconnect(e.message ?: "Handshake error")
            }
        }
    }

    private fun onConnectResponse(response: JSONObject) {
        val payload = response.optJSONObject("payload")
        val type = payload?.optString("type")
        Log.d(TAG, "Connect response type=$type")

        when (type) {
            "hello-ok" -> {
                connectedNodeId = payload.optString("nodeId", "")
                Log.i(TAG, "✅ Connected to gateway — nodeId=$connectedNodeId")
                _connectionState.value = ConnectionState.Connected
                reconnectDelayMs = RECONNECT_DELAY_BASE_MS  // reset backoff
            }
            else -> {
                val reason = payload?.optString("reason", "Auth failed") ?: "Auth rejected"
                Log.e(TAG, "hello-error: $reason")
                onDisconnect("Auth failed: $reason")
            }
        }
    }

    // ── Disconnect & reconnect ─────────────────────────────────

    private fun onDisconnect(reason: String) {
        webSocket = null
        connectedNodeId = null
        cancelAllPending(reason)
        pendingChatRuns.values.forEach { it.completeExceptionally(Exception(reason)) }
        pendingChatRuns.clear()

        if (isUserDisconnected) {
            _connectionState.value = ConnectionState.Disconnected
        } else {
            _connectionState.value = ConnectionState.Error(reason)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.d(TAG, "Reconnect in ${reconnectDelayMs}ms...")
            delay(reconnectDelayMs)
            reconnectDelayMs = minOf(reconnectDelayMs * 2, RECONNECT_DELAY_MAX_MS)
            if (!isUserDisconnected) openWebSocket()
        }
    }

    // ── RPC helpers ────────────────────────────────────────────

    private suspend fun rpc(
        method: String,
        params: JSONObject,
        timeoutMs: Long = RPC_TIMEOUT_MS,
    ): JSONObject {
        val id = generateId()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred

        val frame = buildRpcFrame(id, method, params)

        if (!sendFrame(frame.toString())) {
            pending.remove(id)
            throw Exception("WebSocket send failed for method=$method")
        }

        return withTimeout(timeoutMs) { deferred.await() }
    }

    private fun sendFrame(text: String): Boolean {
        val ws = webSocket ?: return false
        Log.v(TAG, "→ ${text.take(200)}")
        return ws.send(text)
    }

    private fun cancelAllPending(reason: String) {
        pending.values.forEach { it.completeExceptionally(Exception(reason)) }
        pending.clear()
    }

    private fun generateId(): String = UUID.randomUUID().toString()
}
