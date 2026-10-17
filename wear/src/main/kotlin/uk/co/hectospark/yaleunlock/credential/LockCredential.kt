package uk.co.hectospark.yaleunlock.credential

/**
 * The same offline key and key index (slot) Home Assistant's `yalexs_ble`
 * config entry holds, plus the identity fields used to find the lock.
 *
 * [toString] is redacted so the key cannot reach a log line or crash report
 * by accident.
 */
class LockCredential(
    key: ByteArray,
    val slot: Int,
    /** 7-character advertised name (serial-derived); the stable way to find the lock. */
    val localName: String?,
    /** Last known BLE address; a fallback, since the advertised name is preferred. */
    val address: String?,
) {
    init {
        require(key.size == KEY_LEN) { "key must be $KEY_LEN bytes" }
        require(slot in 0..0xFF) { "slot must be 0-255" }
        require(localName == null || LOCAL_NAME.matches(localName)) { "invalid local name" }
        require(address == null || ADDRESS.matches(address)) { "invalid address" }
    }

    private val keyBytes = key.copyOf()

    /** A fresh copy; the caller should wipe it when done. */
    fun keyCopy(): ByteArray = keyBytes.copyOf()

    fun withIdentity(localName: String?, address: String?) =
        LockCredential(keyBytes, slot, localName ?: this.localName, address ?: this.address)

    fun wipe() = keyBytes.fill(0)

    override fun toString(): String = "LockCredential(key=<redacted>, slot=<redacted>, named=${localName != null})"

    companion object {
        const val KEY_LEN = 16
        private val HEX_KEY = Regex("^[0-9a-fA-F]{32}$")
        private val LOCAL_NAME = Regex("^[\\x21-\\x7E]{1,29}$")
        private val ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        fun parseKeyHex(hex: String): ByteArray {
            val trimmed = hex.trim()
            require(HEX_KEY.matches(trimmed)) { "key must be 32 hexadecimal characters" }
            return ByteArray(KEY_LEN) { trimmed.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        fun normaliseAddress(address: String?): String? =
            address?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

        /**
         * Yale modules advertise a NUL-terminated name and Home Assistant
         * stores it verbatim (6 characters + NUL). Compare without terminators.
         */
        fun normaliseLocalName(name: String?): String? =
            name?.trimEnd('\u0000')?.trim()?.takeIf { it.isNotEmpty() }
    }
}
