package ua.tvlight.browser

import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/** Portable authenticated backup. Never stores the passphrase. */
object BackupCrypto {
    private const val ITERATIONS = 210000
    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(key, "AES")
        } finally { spec.clearPassword() }
    }
    fun encrypt(data: String, password: CharArray): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        val json = JSONObject().put("format", "tvlight-backup-v1")
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        return json.toString().toByteArray(Charsets.UTF_8)
    }
    fun decrypt(bytes: ByteArray, password: CharArray): String {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.getString("format") == "tvlight-backup-v1") { "Невідомий формат копії" }
        val salt = Base64.decode(json.getString("salt"), Base64.NO_WRAP)
        val nonce = Base64.decode(json.getString("nonce"), Base64.NO_WRAP)
        val data = Base64.decode(json.getString("data"), Base64.NO_WRAP)
        require(salt.size == 16 && nonce.size == 12 && data.size <= 5_000_000) { "Некоректний файл" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, nonce))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }
}
