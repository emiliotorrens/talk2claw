package com.emiliotorrens.talk2claw.openclaw

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages a persistent Ed25519 device keypair for gateway authentication.
 *
 * Uses BouncyCastle for Ed25519 (Android's JCA support varies by version).
 *
 * The device ID is the full SHA-256 hex digest of the raw 32-byte public key.
 * The signature payload follows the gateway v2 protocol:
 *   v2|deviceId|clientId|clientMode|role|scopes|signedAtMs|token|nonce
 */
class DeviceIdentity private constructor(
    val deviceId: String,
    private val publicKeyRaw: ByteArray,    // 32 bytes
    private val privateKeyRaw: ByteArray,   // 32 bytes
) {
    companion object {
        private const val TAG = "DeviceIdentity"
        private const val PREFS_NAME = "device_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_KEY = "public_key_raw"
        private const val KEY_PRIVATE_KEY = "private_key_raw"

        /** Load existing identity or generate a new one. Returns null on failure. */
        fun loadOrCreate(context: Context): DeviceIdentity? {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val existingPub = prefs.getString(KEY_PUBLIC_KEY, null)
                val existingPriv = prefs.getString(KEY_PRIVATE_KEY, null)

                if (existingPub != null && existingPriv != null) {
                    try {
                        val pubRaw = Base64.decode(existingPub, Base64.NO_WRAP)
                        val privRaw = Base64.decode(existingPriv, Base64.NO_WRAP)
                        val derivedId = sha256Hex(pubRaw)
                        Log.d(TAG, "Loaded existing identity: ${derivedId.take(16)}...")
                        return DeviceIdentity(derivedId, pubRaw, privRaw)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load identity, regenerating: ${e.message}")
                    }
                }

                // Generate new Ed25519 keypair via BouncyCastle
                val kpg = Ed25519KeyPairGenerator()
                kpg.init(Ed25519KeyGenerationParameters(SecureRandom()))
                val keyPair = kpg.generateKeyPair()

                val pubParams = keyPair.public as Ed25519PublicKeyParameters
                val privParams = keyPair.private as Ed25519PrivateKeyParameters
                val pubRaw = pubParams.encoded   // 32 bytes
                val privRaw = privParams.encoded  // 32 bytes
                val deviceId = sha256Hex(pubRaw)

                // Persist
                prefs.edit()
                    .putString(KEY_DEVICE_ID, deviceId)
                    .putString(KEY_PUBLIC_KEY, Base64.encodeToString(pubRaw, Base64.NO_WRAP))
                    .putString(KEY_PRIVATE_KEY, Base64.encodeToString(privRaw, Base64.NO_WRAP))
                    .apply()

                Log.i(TAG, "Generated new identity: ${deviceId.take(16)}...")
                DeviceIdentity(deviceId, pubRaw, privRaw)
            } catch (e: Exception) {
                Log.e(TAG, "DeviceIdentity init failed: ${e.message}", e)
                null
            }
        }

        private fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    /** Public key as base64url (raw 32 bytes). */
    val publicKeyBase64Url: String
        get() = Base64.encodeToString(publicKeyRaw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /**
     * Build the device object for the connect handshake.
     * Signs the v2 payload: v2|deviceId|clientId|clientMode|role|scopes|signedAtMs|token|nonce
     */
    fun buildDeviceBlock(
        nonce: String,
        clientId: String = "cli",
        clientMode: String = "node",
        role: String = "node",
        scopes: String = "",
        token: String,
    ): JSONObject {
        val signedAt = System.currentTimeMillis()
        val payload = listOf(
            "v2", deviceId, clientId, clientMode, role, scopes,
            signedAt.toString(), token, nonce
        ).joinToString("|")

        val signature = sign(payload)

        return JSONObject().apply {
            put("id", deviceId)
            put("publicKey", publicKeyBase64Url)
            put("signature", signature)
            put("signedAt", signedAt)
            put("nonce", nonce)
        }
    }

    /** Sign a payload string with Ed25519 via BouncyCastle, return base64url. */
    private fun sign(payload: String): String {
        val privParams = Ed25519PrivateKeyParameters(privateKeyRaw, 0)
        val signer = Ed25519Signer()
        signer.init(true, privParams)
        val data = payload.toByteArray(Charsets.UTF_8)
        signer.update(data, 0, data.size)
        val signatureBytes = signer.generateSignature()
        return Base64.encodeToString(signatureBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
