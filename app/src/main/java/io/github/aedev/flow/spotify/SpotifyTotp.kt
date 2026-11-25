/*
 * Ported from sonic-liberation/spotube-plugin-spotify services/TOTP.kt (AGPL-3.0).
 * Replaces the Spotube host CryptoAPI with javax.crypto so it runs standalone in RTQ NOW.
 */
package io.github.aedev.flow.spotify

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SpotifyTotp {
    private val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32Decode(input: String): ByteArray {
        val clean = input.uppercase().trimEnd('=')
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>((clean.length * 5) / 8)
        for (c in clean) {
            val v = BASE32_ALPHABET.indexOf(c)
            require(v >= 0) { "Invalid base32 char: $c" }
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }

    /** RFC 6238 TOTP. [secretBase32] base32 secret, [period] seconds, [digits] otp length. */
    fun generate(secretBase32: String, timestampMillis: Long, period: Int = 30, digits: Int = 6): String {
        val key = base32Decode(secretBase32)
        val counter = timestampMillis / 1000 / period
        val msg = ByteArray(8)
        for (i in 7 downTo 0) {
            msg[i] = ((counter shr ((7 - i) * 8)) and 0xFF).toByte()
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(msg)
        val offset = (hash[hash.size - 1].toInt() and 0x0F)
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (binary % mod).toString().padStart(digits, '0')
    }
}
