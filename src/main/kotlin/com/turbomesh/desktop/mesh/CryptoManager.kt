package com.turbomesh.desktop.mesh

import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class CryptoManager {
    private val prefs = Preferences.userRoot().node("turbomesh/crypto")
    private val keyStore = KeyStore.getInstance("PKCS12").apply { load(null) }
    private val keyPair: KeyPair by lazy { loadOrGenKeyPair() }

    private fun loadOrGenKeyPair(): KeyPair {
        val b64 = prefs.get("keypair_priv", null)
        if (b64 != null) {
            try {
                val privBytes = java.util.Base64.getDecoder().decode(b64)
                val pubBytes = java.util.Base64.getDecoder().decode(prefs.get("keypair_pub", ""))
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privBytes))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                return KeyPair(pub, priv)
            } catch (_: Exception) {}
        }
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        prefs.put("keypair_priv", java.util.Base64.getEncoder().encodeToString(kp.private.encoded))
        prefs.put("keypair_pub", java.util.Base64.getEncoder().encodeToString(kp.public.encoded))
        return kp
    }

    fun getPublicKeyBytes(): ByteArray = keyPair.public.encoded

    fun storePeerPublicKey(peerId: String, publicKeyBytes: ByteArray) {
        prefs.put("pk_$peerId", java.util.Base64.getEncoder().encodeToString(publicKeyBytes))
    }

    fun hasPeerPublicKey(peerId: String) = prefs.get("pk_$peerId", null) != null

    fun encrypt(plaintext: ByteArray, peerId: String): ByteArray? {
        val key = deriveSessionKey(peerId) ?: return null
        return try {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            nonce + cipher.doFinal(plaintext)
        } catch (_: Exception) { null }
    }

    fun decrypt(data: ByteArray, peerId: String): ByteArray? {
        if (data.size <= 12) return null
        return try {
            val key = deriveSessionKey(peerId) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            cipher.doFinal(data.copyOfRange(12, data.size))
        } catch (_: Exception) { null }
    }

    fun derivePairingPin(peerId: String): String? {
        val secret = computeSharedSecret(peerId) ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(secret)
        val raw = ((hash[0].toInt() and 0xFF) shl 16) or ((hash[1].toInt() and 0xFF) shl 8) or (hash[2].toInt() and 0xFF)
        return (abs(raw) % 1_000_000).toString().padStart(6, '0')
    }

    private fun deriveSessionKey(peerId: String): SecretKey? {
        val secret = computeSharedSecret(peerId) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(secret); digest.update(peerId.toByteArray())
        return SecretKeySpec(digest.digest(), "AES")
    }

    private fun computeSharedSecret(peerId: String): ByteArray? {
        val b64 = prefs.get("pk_$peerId", null) ?: return null
        return try {
            val kf = KeyFactory.getInstance("EC")
            val peerKey = kf.generatePublic(X509EncodedKeySpec(java.util.Base64.getDecoder().decode(b64)))
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(keyPair.private)
            ka.doPhase(peerKey, true)
            ka.generateSecret()
        } catch (_: Exception) { null }
    }
}
