package com.emiliotorrens.talk2claw.openclaw

import android.util.Log
import com.emiliotorrens.talk2claw.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP bridge to OpenClaw gateway — sends user messages via /v1/chat/completions
 * and returns Claw's text response.
 */
class OpenClawBridge(private val settings: AppSettings) {

    companion object {
        private const val TAG = "OpenClawBridge"
        private const val MAX_HISTORY_TURNS = 10
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Unknown)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val client = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val pingClient = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private val sessionKey = "agent:main:talk2claw"
    private val conversationHistory = mutableListOf<JSONObject>()

    val isConfigured: Boolean
        get() = settings.gatewayHost.isNotBlank() && settings.gatewayToken.isNotBlank()

    private val baseUrl: String
        get() = "${settings.gatewayHost}:${settings.gatewayPort}/v1/chat/completions"

    /** Ping the gateway to check connectivity */
    suspend fun checkConnection(): ConnectionState = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            _connectionState.value = ConnectionState.NotConfigured
            return@withContext ConnectionState.NotConfigured
        }
        _connectionState.value = ConnectionState.Checking
        try {
            val request = Request.Builder()
                .url(baseUrl)
                .get()
                .addHeader("Authorization", "Bearer ${settings.gatewayToken}")
                .build()
            val response = pingClient.newCall(request).execute()
            val code = response.code
            response.close()
            val state = if (code in 200..499) ConnectionState.Connected else ConnectionState.Error("HTTP $code")
            _connectionState.value = state
            Log.d(TAG, "Gateway check: $state")
            state
        } catch (e: Exception) {
            val state = ConnectionState.Error(e.message ?: "Unknown")
            _connectionState.value = state
            Log.w(TAG, "Gateway unreachable: ${e.message}")
            state
        }
    }

    /** Clear conversation history */
    fun resetConversation() {
        conversationHistory.clear()
        Log.d(TAG, "Conversation reset")
    }

    /**
     * Send a user message to Claw and get the text response.
     * Maintains conversation history for multi-turn context.
     */
    suspend fun sendMessage(userMessage: String): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.failure(Exception("Gateway not configured"))

        // Add user message to history
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

        // Trim history to avoid huge payloads
        if (conversationHistory.size > MAX_HISTORY_TURNS * 2) {
            val trimmed = conversationHistory.takeLast(MAX_HISTORY_TURNS * 2)
            conversationHistory.clear()
            conversationHistory.addAll(trimmed)
        }

        try {
            val messagesArray = JSONArray().apply {
                conversationHistory.forEach { put(it) }
            }
            val body = JSONObject().apply {
                put("model", "openclaw")
                put("messages", messagesArray)
                put("stream", false)
            }

            val request = Request.Builder()
                .url(baseUrl)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${settings.gatewayToken}")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-openclaw-session-key", sessionKey)
                .addHeader("x-openclaw-message-channel", "talk2claw")
                .build()

            Log.d(TAG, "Sending ${conversationHistory.size} messages")
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val statusCode = response.code
            response.close()

            if (statusCode !in 200..299) {
                Log.w(TAG, "HTTP $statusCode: ${responseBody.take(200)}")
                return@withContext Result.failure(Exception("HTTP $statusCode"))
            }

            val json = JSONObject(responseBody)
            val content = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "") ?: ""

            if (content.isNotEmpty()) {
                conversationHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", content)
                })
            }

            Log.d(TAG, "Response: ${content.take(150)}")
            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            Result.failure(e)
        }
    }

    sealed class ConnectionState {
        data object Unknown : ConnectionState()
        data object NotConfigured : ConnectionState()
        data object Checking : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
}
