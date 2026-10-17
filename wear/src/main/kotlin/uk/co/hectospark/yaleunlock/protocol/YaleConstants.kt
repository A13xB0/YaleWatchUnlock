package uk.co.hectospark.yaleunlock.protocol

import java.util.UUID

/**
 * Wire constants of the Yale/August BLE protocol, ported from yalexs-ble 3.3.1
 * (`const.py`), the version Home Assistant 2026.7.4 pins for `yalexs_ble`.
 */
object YaleGatt {
    val SERVICE: UUID = UUID.fromString("0000fe24-0000-1000-8000-00805f9b34fb")

    /** Session (post-handshake) channel: AES-128-CBC, simple checksum. */
    val WRITE: UUID = UUID.fromString("bd4ac611-0b45-11e3-8ffd-0800200c9a66")
    val READ: UUID = UUID.fromString("bd4ac612-0b45-11e3-8ffd-0800200c9a66")

    /** Secure (handshake) channel: AES-128-ECB, security checksum. */
    val SECURE_WRITE: UUID = UUID.fromString("bd4ac613-0b45-11e3-8ffd-0800200c9a66")
    val SECURE_READ: UUID = UUID.fromString("bd4ac614-0b45-11e3-8ffd-0800200c9a66")

    /** Client Characteristic Configuration descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Bluetooth SIG company identifier Home Assistant matches Yale locks on. */
    const val YALE_MANUFACTURER_ID = 465
}

/** Every frame on both channels is 18 bytes: 16 encrypted, 2 trailing in the clear. */
internal const val FRAME_LEN = 0x12
internal const val CIPHER_LEN = 0x10

internal object Opcode {
    const val GETSTATUS = 0x02
    const val UNLOCK = 0x0A

    // Secure-channel opcodes (lock.py `_setup_session` / `_shutdown_connection`).
    const val SEC_KEY_EXCHANGE = 0x01
    const val SEC_KEY_EXCHANGE_RESPONSE = 0x02
    const val SEC_INITIALIZATION = 0x03
    const val SEC_INITIALIZATION_RESPONSE = 0x04
    const val SEC_DISCONNECT = 0x05
    const val SEC_DISCONNECT_RESPONSE = 0x8B
}

internal object StatusType {
    const val LOCK_ONLY = 0x02
}

/** Lock positions as reported by the lock (const.py `LockStatus`). */
enum class LockStatus(val code: Int) {
    UNKNOWN(0x00),
    CALIBRATING(0x01),
    UNLOCKING(0x02),
    UNLOCKED(0x03),
    LOCKING(0x04),
    LOCKED(0x05),
    POL_DISCOVERY(0x06),
    SECURE_MODE(0x0C),
    JAMMED(0x1B),
    ;

    companion object {
        fun fromCode(code: Int): LockStatus? = entries.firstOrNull { it.code == code }
    }
}
