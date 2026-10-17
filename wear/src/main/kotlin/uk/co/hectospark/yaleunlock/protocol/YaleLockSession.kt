package uk.co.hectospark.yaleunlock.protocol

import kotlinx.coroutines.delay

/** The two characteristic pairs a Yale lock exposes under [YaleGatt.SERVICE]. */
enum class YaleChannel { SECURE, SESSION }

/**
 * What the protocol needs from the radio. The Android implementation wraps
 * BluetoothGatt; tests use a scripted fake.
 */
interface FrameTransport {
    suspend fun enableNotifications(channel: YaleChannel)
    suspend fun disableNotifications(channel: YaleChannel)

    /**
     * Writes one frame with a write request (acknowledged write), as yalexs-ble
     * does with `write_gatt_char(..., response=True)`.
     *
     * @throws WriteNotSentException when the stack refused to queue the write,
     *   which guarantees nothing reached the lock.
     */
    suspend fun write(channel: YaleChannel, frame: ByteArray)

    /** Next raw notification on [channel], in arrival order; null on timeout. */
    suspend fun receive(channel: YaleChannel, timeoutMs: Long): ByteArray?

    /** A notification already queued on [channel], without waiting. */
    fun poll(channel: YaleChannel): ByteArray?
}

open class YaleProtocolException(message: String) : Exception(message)

/** Wrong offline key or key index (slot); retrying cannot help. */
class AuthException(message: String) : YaleProtocolException(message)

/** A frame failed its checksum or carried the wrong flag. */
class ResponseException(message: String) : YaleProtocolException(message)

/** The lock did not answer within the protocol timeout. */
class ResponseTimeoutException(message: String) : YaleProtocolException(message)

/** The GATT link dropped. */
class DisconnectedException(message: String) : YaleProtocolException(message)

/** The stack refused to queue a write, so the frame never left the watch. */
class WriteNotSentException(message: String) : YaleProtocolException(message)

/** How the lock answered the one UNLOCK frame. */
sealed interface UnlockAck {
    /** Lock acknowledged; [reported] is set when it also named its position. */
    data class Accepted(val reported: LockStatus?) : UnlockAck

    /** Lock replied that the motor stalled (lock.py `_parse_state`, byte 0x03). */
    data object Jammed : UnlockAck
}

/**
 * One authenticated conversation with a Yale lock: handshake, a status read,
 * a single unlock and the clean disconnect, ported from yalexs-ble 3.3.1
 * `Lock` / `Session` / `SecureSession`.
 *
 * Only those operations exist. There is deliberately no lock, setting or
 * activity-log support.
 *
 * Not thread-safe: one coroutine drives a session from start to finish.
 */
class YaleLockSession(
    private val transport: FrameTransport,
    offlineKey: ByteArray,
    private val keyIndex: Int,
    private val randomBytes: (Int) -> ByteArray,
    private val monotonicMillis: () -> Long,
    private val commandTimeoutMs: Long = COMMAND_TIMEOUT_MS,
) {
    init {
        require(offlineKey.size == CIPHER_LEN) { "offline key must be 16 bytes" }
        require(keyIndex in 0..0xFF) { "key index must fit in one byte" }
    }

    private val offlineKey = offlineKey.copyOf()
    private var secureCipher: ChannelCipher = ChannelCipher.Secure(this.offlineKey)
    private var sessionCipher: ChannelCipher? = null
    private var lastNotificationAt = Long.MIN_VALUE / 2
    private var cooldownEnabled = false
    private var secureEstablished = false
    private var sessionNotifying = false

    /**
     * True from the moment the UNLOCK frame is handed to the radio. If the
     * operation then fails for any reason, the lock may have moved, and the
     * caller must report an ambiguous result instead of retrying.
     */
    var unlockMayHaveBeenSent: Boolean = false
        private set

    /** lock.py `connect` (notification order) + `_setup_session`. */
    suspend fun handshake() {
        // Order matters: secure notifications before the handshake, session ones after.
        transport.enableNotifications(YaleChannel.SECURE)

        val handshakeKeys = randomBytes(CIPHER_LEN)
        try {
            val exchange = Frames.secureCommand(Opcode.SEC_KEY_EXCHANGE, keyIndex)
            handshakeKeys.copyInto(exchange, 0x04, 0x00, 0x08)
            val response = executeSecure(exchange)
            if ((response[0].toInt() and 0xFF) != Opcode.SEC_KEY_EXCHANGE_RESPONSE) {
                throw AuthException("Key or key index rejected during key exchange")
            }
            secureEstablished = true

            val sessionKey = ByteArray(CIPHER_LEN)
            handshakeKeys.copyInto(sessionKey, 0x00, 0x00, 0x08)
            response.copyInto(sessionKey, 0x08, 0x04, 0x0C)
            response.fill(0)
            secureCipher = ChannelCipher.Secure(sessionKey)

            val init = Frames.secureCommand(Opcode.SEC_INITIALIZATION, keyIndex)
            handshakeKeys.copyInto(init, 0x04, 0x08, 0x10)
            val initResponse = executeSecure(init)
            if ((initResponse[0].toInt() and 0xFF) != Opcode.SEC_INITIALIZATION_RESPONSE) {
                throw AuthException("Key or key index rejected during initialisation")
            }
            sessionCipher = ChannelCipher.Session(sessionKey)
            sessionKey.fill(0)
            cooldownEnabled = true
        } finally {
            handshakeKeys.fill(0)
            offlineKey.fill(0)
        }
        transport.enableNotifications(YaleChannel.SESSION)
        sessionNotifying = true
    }

    /** lock.py `lock_status`: GETSTATUS / LOCK_ONLY, position at byte 0x08. */
    suspend fun lockStatus(): LockStatus {
        val response = executeSession(Frames.sessionOperation(Opcode.GETSTATUS, StatusType.LOCK_ONLY)) {
            (it[1].toInt() and 0xFF) == Opcode.GETSTATUS && (it[4].toInt() and 0xFF) == StatusType.LOCK_ONLY
        }
        return LockStatus.fromCode(response[0x08].toInt() and 0xFF) ?: LockStatus.UNKNOWN
    }

    /**
     * lock.py `force_unlock`: a single UNLOCK frame. Never retried here; see
     * [unlockMayHaveBeenSent].
     */
    suspend fun unlock(): UnlockAck {
        check(!unlockMayHaveBeenSent) { "unlock already attempted in this session" }
        val response = executeSession(Frames.sessionCommand(Opcode.UNLOCK), markUnlock = true) {
            (it[1].toInt() and 0xFF) == Opcode.UNLOCK
        }
        // lock.py `_parse_state`: 0xAA+UNLOCK is a plain acknowledgement;
        // 0xBB+UNLOCK may carry the resulting position in byte 0x03.
        if ((response[0].toInt() and 0xFF) == 0xAA) return UnlockAck.Accepted(LockStatus.UNLOCKED)
        return when (val status = LockStatus.fromCode(response[0x03].toInt() and 0xFF)) {
            LockStatus.JAMMED -> UnlockAck.Jammed
            LockStatus.UNLOCKED, LockStatus.UNLOCKING -> UnlockAck.Accepted(status)
            // Home Assistant treats any valid reply as success; so do we.
            else -> UnlockAck.Accepted(null)
        }
    }

    /**
     * lock.py `_shutdown_connection`: stop session notifications, then the
     * secure DISCONNECT (key index forced to 0), then stop secure
     * notifications. Best-effort; never throws.
     */
    suspend fun shutdown() {
        runCatching { if (sessionNotifying) transport.disableNotifications(YaleChannel.SESSION) }
        if (!secureEstablished) return
        runCatching {
            val cmd = Frames.secureCommand(Opcode.SEC_DISCONNECT, keyIndex)
            cmd[0x11] = 0x00
            executeSecure(cmd, timeoutMs = SHUTDOWN_TIMEOUT_MS)
            transport.disableNotifications(YaleChannel.SECURE)
        }
    }

    private suspend fun executeSecure(frame: ByteArray, timeoutMs: Long = commandTimeoutMs): ByteArray {
        Frames.writeSecureChecksum(frame)
        return exchange(YaleChannel.SECURE, frame, timeoutMs, markUnlock = false, valid = Frames::isValidSecureResponse) { true }
    }

    private suspend fun executeSession(
        frame: ByteArray,
        markUnlock: Boolean = false,
        accept: (ByteArray) -> Boolean,
    ): ByteArray {
        checkNotNull(sessionCipher) { "handshake not complete" }
        Frames.writeSessionChecksum(frame)
        return exchange(YaleChannel.SESSION, frame, commandTimeoutMs, markUnlock, Frames::isValidSessionResponse, accept)
    }

    private fun cipherFor(channel: YaleChannel): ChannelCipher = when (channel) {
        YaleChannel.SECURE -> secureCipher
        YaleChannel.SESSION -> checkNotNull(sessionCipher)
    }

    /** Decrypts in place and records the arrival time used by the cooldown. */
    private fun decryptNotification(channel: YaleChannel, raw: ByteArray): ByteArray? {
        lastNotificationAt = monotonicMillis()
        if (raw.size < FRAME_LEN) return null
        // Every notification advances the CBC chain, wanted or not (Session._notify).
        cipherFor(channel).decrypt(raw)
        return raw
    }

    private suspend fun exchange(
        channel: YaleChannel,
        frame: ByteArray,
        timeoutMs: Long,
        markUnlock: Boolean,
        valid: (ByteArray) -> Boolean,
        accept: (ByteArray) -> Boolean,
    ): ByteArray {
        // Session.execute: sending too soon after the last notification can
        // crash the lock's radio until its batteries are pulled.
        if (cooldownEnabled) {
            val wait = COOLDOWN_MS - (monotonicMillis() - lastNotificationAt)
            if (wait > 0) delay(wait)
        }

        // Anything that arrived before this command is unsolicited state; it
        // still has to pass through the cipher to keep the chain aligned.
        while (true) {
            val stale = transport.poll(channel) ?: break
            decryptNotification(channel, stale)?.fill(0)
        }

        cipherFor(channel).encrypt(frame)
        if (markUnlock) unlockMayHaveBeenSent = true
        try {
            transport.write(channel, frame)
        } catch (e: WriteNotSentException) {
            if (markUnlock) unlockMayHaveBeenSent = false
            throw e
        } finally {
            frame.fill(0)
        }

        val deadline = monotonicMillis() + timeoutMs
        while (true) {
            val remaining = deadline - monotonicMillis()
            if (remaining <= 0) throw ResponseTimeoutException("No reply on $channel channel")
            val raw = transport.receive(channel, remaining)
                ?: throw ResponseTimeoutException("No reply on $channel channel")
            val plain = decryptNotification(channel, raw) ?: continue
            if (!valid(plain)) {
                plain.fill(0)
                throw ResponseException("Invalid frame on $channel channel")
            }
            if (accept(plain)) return plain
            // A valid but unrelated push (e.g. a status broadcast): keep waiting.
            plain.fill(0)
        }
    }

    companion object {
        /** session.py `_locked_write` waits 10 s for each reply. */
        const val COMMAND_TIMEOUT_MS = 10_000L
        const val SHUTDOWN_TIMEOUT_MS = 2_000L

        /** session.py COOLDOWN_TIME. */
        const val COOLDOWN_MS = 250L
    }
}
