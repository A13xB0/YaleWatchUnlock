package uk.co.hectospark.yaleunlock.protocol

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Byte-level framing, checksums and ciphers, ported from yalexs-ble 3.3.1
 * `util.py`, `session.py` and `secure_session.py`.
 *
 * Checksums are always computed over the plaintext: the library writes the
 * checksum before encrypting and validates notifications after decrypting.
 */
internal object Frames {

    /** util._simple_checksum: two's-complement of the byte sum of the 18-byte frame. */
    fun simpleChecksum(buf: ByteArray): Int {
        var cs = 0
        for (i in 0 until FRAME_LEN) cs = (cs + (buf[i].toInt() and 0xFF)) and 0xFF
        return (-cs) and 0xFF
    }

    /**
     * util._security_checksum. Python adds three little-endian integers
     * (bytes 0-3, 4-7 and the 10 bytes 8-17) and masks to 32 bits, so only
     * bytes 8-11 of the third term survive the mask.
     */
    fun securityChecksum(buf: ByteArray): Long {
        val sum = u32(buf, 0x00) + u32(buf, 0x04) + u32(buf, 0x08)
        return (-sum) and 0xFFFFFFFFL
    }

    fun u32(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFF) or
            ((buf[offset + 1].toLong() and 0xFF) shl 8) or
            ((buf[offset + 2].toLong() and 0xFF) shl 16) or
            ((buf[offset + 3].toLong() and 0xFF) shl 24)

    private fun putU32(buf: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) buf[offset + i] = (value ushr (8 * i)).toByte()
    }

    /** SecureSession.build_command. */
    fun secureCommand(opcode: Int, keyIndex: Int): ByteArray = ByteArray(FRAME_LEN).also {
        it[0x00] = opcode.toByte()
        it[0x10] = 0x0F
        it[0x11] = keyIndex.toByte()
    }

    /** Session.build_command. */
    fun sessionCommand(opcode: Int): ByteArray = ByteArray(FRAME_LEN).also {
        it[0x00] = 0xEE.toByte()
        it[0x01] = opcode.toByte()
        it[0x10] = 0x02
    }

    /** Session.build_operation_command. */
    fun sessionOperation(opcode: Int, cmdByte: Int): ByteArray =
        sessionCommand(opcode).also { it[0x04] = cmdByte.toByte() }

    /** SecureSession._write_checksum: 32-bit little-endian at 0x0C. */
    fun writeSecureChecksum(frame: ByteArray) = putU32(frame, 0x0C, securityChecksum(frame))

    /** Session._write_checksum: single byte at 0x03. */
    fun writeSessionChecksum(frame: ByteArray) {
        frame[0x03] = simpleChecksum(frame).toByte()
    }

    fun isValidSecureResponse(plain: ByteArray): Boolean =
        plain.size >= FRAME_LEN && u32(plain, 0x0C) == securityChecksum(plain)

    fun isValidSessionResponse(plain: ByteArray): Boolean {
        if (plain.size < FRAME_LEN || simpleChecksum(plain) != 0) return false
        val flag = plain[0].toInt() and 0xFF
        return flag == 0xBB || flag == 0xAA
    }
}

/**
 * One direction-pair of AES state for a channel.
 *
 * Matches the `cryptography` objects yalexs-ble creates in `set_key`:
 * an encryptor and a decryptor that live for the whole session. For the
 * session channel that matters, because CBC state chains from one frame to
 * the next in each direction, so *every* notification must pass through
 * [decrypt] in arrival order or the two sides fall out of step.
 */
internal sealed class ChannelCipher {
    abstract fun encrypt(frame: ByteArray)
    abstract fun decrypt(frame: ByteArray)

    /** SecureSession: AES-ECB, stateless per block. ECB is what the lock speaks; there is no choice. */
    @android.annotation.SuppressLint("GetInstance")
    class Secure(key: ByteArray) : ChannelCipher() {
        private val enc = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        private val dec = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }
        override fun encrypt(frame: ByteArray) = transform(enc, frame)
        override fun decrypt(frame: ByteArray) = transform(dec, frame)
    }

    /** Session: AES-CBC with an all-zero IV whose chain persists across frames. */
    class Session(key: ByteArray) : ChannelCipher() {
        private val zeroIv = IvParameterSpec(ByteArray(CIPHER_LEN))
        private val enc = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), zeroIv) }
        private val dec = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), zeroIv) }
        override fun encrypt(frame: ByteArray) = transform(enc, frame)
        override fun decrypt(frame: ByteArray) = transform(dec, frame)
    }

    /** Only the first 16 bytes are ciphered; the last two travel in the clear. */
    protected fun transform(cipher: Cipher, frame: ByteArray) {
        // update() on exactly one block with NoPadding returns that block and
        // carries the chaining state forward, like the Python CipherContext.
        val out = cipher.update(frame, 0, CIPHER_LEN)
        out.copyInto(frame, 0, 0, CIPHER_LEN)
        out.fill(0)
    }
}
