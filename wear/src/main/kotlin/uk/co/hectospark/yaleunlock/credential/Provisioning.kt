package uk.co.hectospark.yaleunlock.credential

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Explicit, time-boxed authorisation for receiving a credential over ADB.
 *
 * The user arms it on the watch; it disarms itself after [WINDOW_MS], after
 * one delivery, or when the setup screen closes. State is in-memory on
 * purpose: a process restart always disarms.
 */
object ProvisioningGate {
    const val WINDOW_MS = 3 * 60 * 1000L

    sealed interface State {
        data object Idle : State
        data class Armed(val untilElapsed: Long) : State
        data object Received : State
        data class Rejected(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun arm() {
        _state.value = State.Armed(SystemClock.elapsedRealtime() + WINDOW_MS)
    }

    fun disarm() {
        if (_state.value is State.Armed) _state.value = State.Idle
    }

    fun reset() {
        _state.value = State.Idle
    }

    /** Atomically consumes the authorisation so one arming accepts one delivery. */
    @Synchronized
    internal fun consume(): Boolean {
        val s = _state.value
        if (s !is State.Armed || SystemClock.elapsedRealtime() > s.untilElapsed) {
            if (s is State.Armed) _state.value = State.Idle
            return false
        }
        _state.value = State.Idle
        return true
    }

    internal fun report(result: State) {
        _state.value = result
    }
}

/**
 * Receives a credential piped from `adb shell content write`.
 *
 * Who can reach it:
 * - The manifest guards it with `android.permission.DUMP`, which ordinary
 *   apps cannot hold but the ADB shell does.
 * - [enforceShell] additionally accepts only the shell and root UIDs.
 * - [ProvisioningGate] must have been armed on the watch within the last
 *   three minutes, and accepts a single delivery.
 *
 * The payload is the `data` object of Home Assistant's `yalexs_ble` config
 * entry (`key`, `slot`, `local_name`, `address`), streamed on stdin so it never
 * appears in a command line on the host or the watch.
 */
class ProvisioningProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforceShell()
        if (uri.path != PATH_CREDENTIAL) throw FileNotFoundException("unknown path")
        // The pipe only ever carries data into the app, whatever mode the shell asks for.
        if (!mode.contains('w')) throw FileNotFoundException("write-only")
        if (!ProvisioningGate.consume()) {
            throw SecurityException("Not armed: open Setup > Provision over ADB on the watch first")
        }
        val context = requireNotNull(context).applicationContext
        val (readSide, writeSide) = ParcelFileDescriptor.createReliablePipe()
        thread(name = "credential-provisioning", isDaemon = true) {
            receive(context, ParcelFileDescriptor.AutoCloseInputStream(readSide))
        }
        return writeSide
    }

    private fun receive(context: Context, input: InputStream) {
        var payload: ByteArray? = null
        try {
            payload = input.use { readBounded(it) }
            val credential = parse(payload)
            try {
                CredentialStore.get(context).save(credential)
            } finally {
                credential.wipe()
            }
            Log.i(TAG, "Credential provisioned")
            ProvisioningGate.report(ProvisioningGate.State.Received)
        } catch (e: IllegalArgumentException) {
            // Validation messages describe format only, never content.
            Log.w(TAG, "Provisioning rejected: ${e.message}")
            ProvisioningGate.report(ProvisioningGate.State.Rejected(e.message ?: "invalid payload"))
        } catch (e: Exception) {
            Log.w(TAG, "Provisioning failed: ${e.javaClass.simpleName}")
            ProvisioningGate.report(ProvisioningGate.State.Rejected("could not store credential"))
        } finally {
            payload?.fill(0)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceShell()
        if (uri.path != PATH_STATUS) throw IllegalArgumentException("unknown path")
        val store = CredentialStore.get(requireNotNull(context))
        val gate = ProvisioningGate.state.value
        val result = when (gate) {
            ProvisioningGate.State.Idle -> "idle"
            is ProvisioningGate.State.Armed -> "armed"
            ProvisioningGate.State.Received -> "received"
            is ProvisioningGate.State.Rejected -> "rejected"
        }
        // Outcome only; never any credential content.
        return MatrixCursor(arrayOf("armed", "provisioned", "result")).apply {
            addRow(arrayOf<Any>(if (gate is ProvisioningGate.State.Armed) 1 else 0, if (store.provisioned.value) 1 else 0, result))
        }
    }

    private fun enforceShell() {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("ADB shell only")
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private const val TAG = "Provisioning"
        const val PATH_CREDENTIAL = "/credential"
        const val PATH_STATUS = "/status"
        private const val MAX_PAYLOAD = 4096
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0

        private fun readBounded(input: InputStream): ByteArray {
            val buf = ByteArray(MAX_PAYLOAD)
            var n = 0
            while (true) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
                require(n < MAX_PAYLOAD) { "payload too large" }
            }
            return buf.copyOf(n).also { buf.fill(0) }
        }

        /** Parses Home Assistant's `yalexs_ble` entry data. Exposed for tests. */
        internal fun parse(payload: ByteArray): LockCredential {
            val json = try {
                JSONObject(String(payload, Charsets.UTF_8))
            } catch (e: org.json.JSONException) {
                throw IllegalArgumentException("payload is not a JSON object")
            }
            require(json.has("key") && json.has("slot")) { "payload needs key and slot" }
            val key = LockCredential.parseKeyHex(json.getString("key"))
            try {
                val slot = json.optInt("slot", -1)
                return LockCredential(
                    key = key,
                    slot = slot,
                    localName = LockCredential.normaliseLocalName(json.optString("local_name")),
                    address = LockCredential.normaliseAddress(json.optString("address")),
                )
            } finally {
                key.fill(0)
            }
        }
    }
}
