package com.manegow.data.crypto

import android.util.Base64
import java.security.*
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptographyManager {

    companion object {
        private const val ALGORITHM = "EC"
        private const val CURVE = "secp256r1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH = 128
        private const val IV_LENGTH = 12
    }

    fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(ALGORITHM)
        keyPairGenerator.initialize(256)
        return keyPairGenerator.generateKeyPair()
    }

    fun getSharedSecret(privateKey: PrivateKey, remotePublicKeyString: String): SecretKeySpec {
        val publicKeyBytes = Base64.decode(remotePublicKeyString, Base64.DEFAULT)
        val keyFactory = KeyFactory.getInstance(ALGORITHM)
        val remotePublicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(remotePublicKey, true)
        
        val secret = keyAgreement.generateSecret()
        return SecretKeySpec(secret.take(32).toByteArray(), "AES")
    }

    fun encrypt(plainText: String, secretKey: SecretKeySpec): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH, iv))
        
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + cipherText
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    fun decrypt(encryptedBase64: String, secretKey: SecretKeySpec): String {
        val combined = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val iv = combined.sliceArray(0 until IV_LENGTH)
        val cipherText = combined.sliceArray(IV_LENGTH until combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH, iv))
        
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
