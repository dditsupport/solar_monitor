package com.dangeedums.solar.ble

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BLE challenge-response authentication shared with the firmware.
 *
 * BLE can't be left open — anyone in range could otherwise read the buffered
 * energy log or push Wi-Fi credentials. Instead the app and the firmware hold a
 * secret pre-shared key that is **never** sent over the air. The firmware
 * publishes a random nonce on the Auth Challenge characteristic; the app
 * replies with `HMAC_SHA256(key = PRESHARED_KEY, msg = nonce)` on the Auth
 * Response characteristic. A passive sniffer only ever sees the nonce and the
 * digest, neither of which reveals the key, and the nonce rotates on every
 * connection and every failed attempt so responses can't be replayed.
 *
 * [PRESHARED_KEY] MUST match `BLE_PRESHARED_KEY` in the firmware's `config.h`
 * exactly (same UTF-8 bytes). Change both together before deploying.
 */
object BleAuth {

    const val PRESHARED_KEY: String = "change-me-solar-monitor-preshared-key-v1"

    /**
     * Compute the response to a challenge: the hex-encoded
     * HMAC-SHA256 of the nonce bytes, keyed by [key].
     *
     * @param nonceHex the nonce as served by the firmware (lowercase hex).
     */
    fun response(nonceHex: String, key: String = PRESHARED_KEY): String {
        val nonce = hexToBytes(nonceHex)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return bytesToHex(mac.doFinal(nonce))
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex string" }
        return ByteArray(hex.length / 2) { i ->
            ((hexNibble(hex[i * 2]) shl 4) or hexNibble(hex[i * 2 + 1])).toByte()
        }
    }

    private fun hexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("bad hex digit: $c")
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        val hex = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = hex[v ushr 4]
            out[i * 2 + 1] = hex[v and 0x0f]
        }
        return String(out)
    }
}
