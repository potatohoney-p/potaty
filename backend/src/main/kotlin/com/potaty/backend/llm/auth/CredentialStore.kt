/*
 * Copyright (c) 2026, Potaty
 *
 * Server-side, envelope-encrypted credential store (plan section 3.3).
 *
 * Secrets are encrypted at rest and resolved ONLY server-side, immediately before a provider
 * call. The plaintext secret is never serialized into any DTO, log, or API response.
 *
 * SCAFFOLD: the EnvelopeCipher below is a local-dev AES/GCM implementation keyed by a master
 * key reference. In production the master key is a KMS-managed key (plan: "envelope-encrypted
 * with KMS or a local dev key hierarchy"). The interface is what matters; swap the cipher.
 */

package com.potaty.backend.llm.auth

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Opaque handle to a stored secret. Stored on the credential row (encrypted_secret_ref). */
data class EncryptedSecretRef(val value: String)

interface CredentialStore {
    /** Encrypts [plaintextSecret] and returns a ref to persist. Plaintext is not retained. */
    fun seal(workspaceId: String, plaintextSecret: String): EncryptedSecretRef

    /** Resolves a ref back to plaintext for an outbound provider call. Server-side only. */
    fun open(workspaceId: String, ref: EncryptedSecretRef): String
}

/**
 * Local/dev envelope cipher. Derives a per-call key material from the master key reference.
 * NOT for production secret management — replace with KMS-backed envelope encryption.
 */
class EnvelopeCredentialStore(masterKeyRef: String) : CredentialStore {

    // Derive a 256-bit key from the reference (dev only). Production: KMS GenerateDataKey.
    private val key: SecretKeySpec = run {
        val raw = java.security.MessageDigest.getInstance("SHA-256")
            .digest(masterKeyRef.toByteArray(Charsets.UTF_8))
        SecretKeySpec(raw, "AES")
    }
    private val random = SecureRandom()

    override fun seal(workspaceId: String, plaintextSecret: String): EncryptedSecretRef {
        val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        // Bind the workspace as AAD so a ref cannot be replayed across tenants.
        cipher.updateAAD(workspaceId.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plaintextSecret.toByteArray(Charsets.UTF_8))
        val packed = iv + ct
        return EncryptedSecretRef(Base64.getEncoder().encodeToString(packed))
    }

    override fun open(workspaceId: String, ref: EncryptedSecretRef): String {
        val packed = Base64.getDecoder().decode(ref.value)
        val iv = packed.copyOfRange(0, GCM_IV_BYTES)
        val ct = packed.copyOfRange(GCM_IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(workspaceId.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
