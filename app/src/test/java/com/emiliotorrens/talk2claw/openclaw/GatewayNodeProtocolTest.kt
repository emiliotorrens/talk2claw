package com.emiliotorrens.talk2claw.openclaw

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the OpenClaw WebSocket protocol message construction and parsing.
 * Tests the internal companion helpers in [GatewayNode] without needing a live connection.
 */
class GatewayNodeProtocolTest {

    private val testToken = "test-secret-token-12345"
    private val testId    = "req-abc-123"

    // ── Connect request (handshake) ───────────────────────────────────────────

    @Test
    fun `buildConnectParams has correct protocol version`() {
        val params = GatewayNode.buildConnectParams(testToken)
        assertEquals("minProtocol should be 3", 3, params.getInt("minProtocol"))
        assertEquals("maxProtocol should be 3", 3, params.getInt("maxProtocol"))
    }

    @Test
    fun `buildConnectParams has role node`() {
        val params = GatewayNode.buildConnectParams(testToken)
        assertEquals("role must be 'operator'", "operator", params.getString("role"))
    }

    @Test
    fun `buildConnectParams embeds auth token`() {
        val params = GatewayNode.buildConnectParams(testToken)
        val auth = params.getJSONObject("auth")
        assertEquals("auth.token must match provided token", testToken, auth.getString("token"))
    }

    @Test
    fun `buildConnectParams has client info`() {
        val params = GatewayNode.buildConnectParams(testToken)
        val client = params.getJSONObject("client")
        assertEquals("client.id", "cli", client.getString("id"))
        assertEquals("client.platform", "android", client.getString("platform"))
        assertEquals("client.mode", "node", client.getString("mode"))
        assertFalse("client.version should not be empty",
            client.getString("version").isBlank())
    }

    @Test
    fun `buildConnectParams has voice capability`() {
        val params = GatewayNode.buildConnectParams(testToken)
        val caps = params.getJSONArray("caps")
        val capList = (0 until caps.length()).map { caps.getString(it) }
        assertTrue("caps must contain 'voice'", "voice" in capList)
    }

    @Test
    fun `buildConnectParams has required fields for gateway`() {
        val params = GatewayNode.buildConnectParams(testToken)
        // These fields are required by the OpenClaw gateway protocol
        assertTrue(params.has("scopes"))
        assertTrue(params.has("commands"))
        assertTrue(params.has("permissions"))
        assertTrue(params.has("locale"))
        assertTrue(params.has("userAgent"))
    }

    // ── RPC frame format ──────────────────────────────────────────────────────

    @Test
    fun `buildRpcFrame has correct type field`() {
        val params = JSONObject().apply { put("key", "value") }
        val frame = GatewayNode.buildRpcFrame(testId, "chat.send", params)
        assertEquals("type must be 'req'", "req", frame.getString("type"))
    }

    @Test
    fun `buildRpcFrame preserves id`() {
        val params = JSONObject()
        val frame = GatewayNode.buildRpcFrame(testId, "chat.history", params)
        assertEquals("id must be preserved", testId, frame.getString("id"))
    }

    @Test
    fun `buildRpcFrame preserves method`() {
        val params = JSONObject()
        val frame = GatewayNode.buildRpcFrame(testId, "chat.send", params)
        assertEquals("method must be preserved", "chat.send", frame.getString("method"))
    }

    @Test
    fun `buildRpcFrame embeds params`() {
        val params = JSONObject().apply {
            put("message", "Hola Claw")
            put("sessionKey", "main")
        }
        val frame = GatewayNode.buildRpcFrame(testId, "chat.send", params)
        val embeddedParams = frame.getJSONObject("params")
        assertEquals("params.message preserved", "Hola Claw", embeddedParams.getString("message"))
        assertEquals("params.sessionKey preserved", "main", embeddedParams.getString("sessionKey"))
    }

    @Test
    fun `connect frame built with buildRpcFrame has correct structure`() {
        val params = GatewayNode.buildConnectParams(testToken)
        val frame = GatewayNode.buildRpcFrame(testId, "connect", params)

        assertEquals("type", "req", frame.getString("type"))
        assertEquals("method", "connect", frame.getString("method"))
        assertEquals("id", testId, frame.getString("id"))

        // Verify params are embedded correctly
        val embeddedParams = frame.getJSONObject("params")
        assertEquals("minProtocol inside frame", 3, embeddedParams.getInt("minProtocol"))
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    @Test
    fun `extractChatText reads message field`() {
        val response = JSONObject().apply {
            put("payload", JSONObject().apply {
                put("message", "Hola, estoy aquí para ayudarte.")
            })
        }
        assertEquals(
            "Should extract 'message' field",
            "Hola, estoy aquí para ayudarte.",
            GatewayNode.extractChatText(response)
        )
    }

    @Test
    fun `extractChatText falls back to content field`() {
        val response = JSONObject().apply {
            put("payload", JSONObject().apply {
                put("content", "Respuesta usando campo content.")
            })
        }
        assertEquals(
            "Should fall back to 'content' field",
            "Respuesta usando campo content.",
            GatewayNode.extractChatText(response)
        )
    }

    @Test
    fun `extractChatText falls back to text field`() {
        val response = JSONObject().apply {
            put("payload", JSONObject().apply {
                put("text", "Respuesta usando campo text.")
            })
        }
        assertEquals(
            "Should fall back to 'text' field",
            "Respuesta usando campo text.",
            GatewayNode.extractChatText(response)
        )
    }

    @Test
    fun `extractChatText falls back to response field`() {
        val payload = JSONObject().apply {
            put("response", "Respuesta usando campo response.")
        }
        val response = JSONObject().apply { put("payload", payload) }
        assertEquals(
            "Should fall back to 'response' field",
            "Respuesta usando campo response.",
            GatewayNode.extractChatText(response)
        )
    }

    @Test
    fun `extractChatText works without payload wrapper`() {
        // Some responses have content at the top level
        val response = JSONObject().apply {
            put("message", "Respuesta directa sin payload.")
        }
        assertEquals(
            "Should work with content at top level",
            "Respuesta directa sin payload.",
            GatewayNode.extractChatText(response)
        )
    }

    @Test
    fun `extractChatText returns empty for unknown fields`() {
        val response = JSONObject().apply {
            put("payload", JSONObject().apply {
                put("unknownField", "should not be extracted")
            })
        }
        assertEquals(
            "Should return empty string when no known field present",
            "",
            GatewayNode.extractChatText(response)
        )
    }

    // ── Error response parsing ────────────────────────────────────────────────

    @Test
    fun `extractRpcError reads message from error object`() {
        val response = JSONObject().apply {
            put("ok", false)
            put("error", JSONObject().apply {
                put("message", "Authentication failed: invalid token")
                put("code", 401)
            })
        }
        val error = GatewayNode.extractRpcError(response)
        assertEquals(
            "Should extract error.message",
            "Authentication failed: invalid token",
            error
        )
    }

    @Test
    fun `extractRpcError reads string error field as fallback`() {
        val response = JSONObject().apply {
            put("ok", false)
            put("error", "Simple string error message")
        }
        val error = GatewayNode.extractRpcError(response)
        assertEquals(
            "Should read string error field",
            "Simple string error message",
            error
        )
    }

    @Test
    fun `extractRpcError returns default when no error field`() {
        val response = JSONObject().apply {
            put("ok", false)
        }
        val error = GatewayNode.extractRpcError(response)
        assertEquals("Should return default message", "RPC error", error)
    }

    // ── Integration: full handshake frame ─────────────────────────────────────

    @Test
    fun `full connect frame is valid JSON with all required fields`() {
        val params = GatewayNode.buildConnectParams(testToken)
        val frame = GatewayNode.buildRpcFrame(testId, "connect", params)
        val json = frame.toString()

        // Should be valid JSON (re-parse it)
        val reparsed = JSONObject(json)
        assertEquals("req", reparsed.getString("type"))
        assertEquals("connect", reparsed.getString("method"))
        assertEquals(testId, reparsed.getString("id"))

        val reparsedParams = reparsed.getJSONObject("params")
        val reparsedAuth = reparsedParams.getJSONObject("auth")
        assertEquals(testToken, reparsedAuth.getString("token"))
        assertEquals(3, reparsedParams.getInt("minProtocol"))
        assertEquals("operator", reparsedParams.getString("role"))
    }

    // ── Constants sanity check ────────────────────────────────────────────────

    @Test
    fun `RPC timeout is reasonable (between 5s and 120s)`() {
        assertTrue("RPC_TIMEOUT_MS should be > 5000", GatewayNode.RPC_TIMEOUT_MS > 5_000L)
        assertTrue("RPC_TIMEOUT_MS should be < 120_000", GatewayNode.RPC_TIMEOUT_MS < 120_000L)
    }

    @Test
    fun `handshake timeout is shorter than RPC timeout`() {
        assertTrue(
            "Handshake timeout should be <= RPC timeout",
            GatewayNode.HANDSHAKE_TIMEOUT_MS <= GatewayNode.RPC_TIMEOUT_MS
        )
    }

    @Test
    fun `reconnect base delay is shorter than max delay`() {
        assertTrue(
            "Base reconnect delay should be less than max",
            GatewayNode.RECONNECT_DELAY_BASE_MS < GatewayNode.RECONNECT_DELAY_MAX_MS
        )
    }
}
