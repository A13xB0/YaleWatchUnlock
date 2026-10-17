package uk.co.hectospark.yaleunlock.unlock

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.hectospark.yaleunlock.ble.GattException
import uk.co.hectospark.yaleunlock.ble.GattTransport
import uk.co.hectospark.yaleunlock.ble.LockScanner
import uk.co.hectospark.yaleunlock.ble.ScanFailedException
import uk.co.hectospark.yaleunlock.credential.CredentialStore
import uk.co.hectospark.yaleunlock.credential.LockCredential
import uk.co.hectospark.yaleunlock.protocol.AuthException
import uk.co.hectospark.yaleunlock.protocol.DisconnectedException
import uk.co.hectospark.yaleunlock.protocol.LockStatus
import uk.co.hectospark.yaleunlock.protocol.ResponseException
import uk.co.hectospark.yaleunlock.protocol.ResponseTimeoutException
import uk.co.hectospark.yaleunlock.protocol.UnlockAck
import uk.co.hectospark.yaleunlock.protocol.WriteNotSentException
import uk.co.hectospark.yaleunlock.protocol.YaleLockSession

enum class Stage { PREPARING, SCANNING, CONNECTING, AUTHENTICATING, CHECKING, UNLOCKING, DISCONNECTING }

enum class Failure {
    NOT_PROVISIONED,
    WATCH_LOCKED,
    CREDENTIAL_UNREADABLE,
    PERMISSION_MISSING,
    BLUETOOTH_OFF,
    LOCK_NOT_FOUND,
    CONNECTION_FAILED,
    AUTH_REJECTED,
    LOCK_ERROR,
    NOT_SENT,
}

sealed interface UnlockResult {
    data class Unlocked(val reported: LockStatus?) : UnlockResult
    data object AlreadyUnlocked : UnlockResult
    data object Jammed : UnlockResult

    /**
     * The UNLOCK frame may have reached the lock, but no valid reply came
     * back. Never retried automatically. [statusAfter] is a best-effort
     * read-back on the same link, if one was possible.
     */
    data class Ambiguous(val statusAfter: LockStatus?) : UnlockResult
    data class Failed(val reason: Failure) : UnlockResult
}

/**
 * Runs unlocks in two halves so a hold gesture can prepare early:
 *
 * - [prepare]: scan, connect, handshake and status read. Nothing here can
 *   move the latch, so one fresh-connection retry is allowed for transient
 *   radio errors.
 * - [commit]: the single UNLOCK frame. Never retried; once the frame may
 *   have been sent, any failure is [UnlockResult.Ambiguous].
 * - [release]: clean disconnect, always.
 *
 * [run] chains the three for a plain tap.
 */
class UnlockEngine(private val context: Context) {

    private val store = CredentialStore.get(context)
    private val random = SecureRandom()

    /** An authenticated link to the lock, ready for one UNLOCK. */
    class Ready internal constructor(
        internal val transport: GattTransport,
        internal val session: YaleLockSession,
    )

    sealed interface Prepared {
        data class Connected(val ready: Ready) : Prepared

        /** Finished without needing an UNLOCK (already unlocked, or a failure). */
        data class Done(val result: UnlockResult) : Prepared
    }

    suspend fun run(onStage: (Stage) -> Unit): UnlockResult =
        when (val prepared = prepare(onStage)) {
            is Prepared.Done -> prepared.result
            is Prepared.Connected -> try {
                commit(prepared.ready, onStage)
            } finally {
                release(prepared.ready, onStage)
            }
        }

    suspend fun prepare(onStage: (Stage) -> Unit): Prepared {
        onStage(Stage.PREPARING)
        if (!hasPermissions()) return Prepared.Done(UnlockResult.Failed(Failure.PERMISSION_MISSING))
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return Prepared.Done(UnlockResult.Failed(Failure.BLUETOOTH_OFF))

        val credential = when (val loaded = store.load()) {
            is CredentialStore.LoadResult.Loaded -> loaded.credential
            CredentialStore.LoadResult.Missing -> return Prepared.Done(UnlockResult.Failed(Failure.NOT_PROVISIONED))
            CredentialStore.LoadResult.Unavailable -> return Prepared.Done(UnlockResult.Failed(Failure.WATCH_LOCKED))
            CredentialStore.LoadResult.Corrupt -> return Prepared.Done(UnlockResult.Failed(Failure.CREDENTIAL_UNREADABLE))
        }
        try {
            var last: Failure = Failure.CONNECTION_FAILED
            for (attempt in 1..ATTEMPTS) {
                when (val outcome = attempt(adapter, credential, attempt, onStage)) {
                    is Attempt.Done -> return outcome.prepared
                    is Attempt.Retryable -> last = outcome.reason
                }
                if (attempt < ATTEMPTS) delay(RETRY_DELAY_MS)
            }
            return Prepared.Done(UnlockResult.Failed(last))
        } finally {
            credential.wipe()
        }
    }

    /** Sends the one UNLOCK on a prepared link. The caller must [release] afterwards. */
    suspend fun commit(ready: Ready, onStage: (Stage) -> Unit): UnlockResult {
        val session = ready.session
        return try {
            onStage(Stage.UNLOCKING)
            val result = when (val ack = session.unlock()) {
                is UnlockAck.Accepted -> UnlockResult.Unlocked(ack.reported)
                UnlockAck.Jammed -> UnlockResult.Jammed
            }
            Log.i(TAG, "Unlock acknowledged: ${result.javaClass.simpleName}")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unlock failed at ${e.javaClass.simpleName}")
            if (session.unlockMayHaveBeenSent) {
                val after = withTimeoutOrNull(READBACK_TIMEOUT_MS) { runCatching { session.lockStatus() }.getOrNull() }
                UnlockResult.Ambiguous(after)
            } else {
                // Refused by the local stack before leaving the watch.
                UnlockResult.Failed(Failure.NOT_SENT)
            }
        }
    }

    /** Clean secure disconnect and GATT close. Never throws, never cancelled half-way. */
    suspend fun release(ready: Ready, onStage: (Stage) -> Unit) {
        onStage(Stage.DISCONNECTING)
        withContext(NonCancellable) {
            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { ready.session.shutdown() }
            ready.transport.close()
        }
    }

    private sealed interface Attempt {
        data class Done(val prepared: Prepared) : Attempt
        data class Retryable(val reason: Failure) : Attempt
    }

    private suspend fun attempt(
        adapter: BluetoothAdapter,
        credential: LockCredential,
        attempt: Int,
        onStage: (Stage) -> Unit,
    ): Attempt {
        onStage(Stage.SCANNING)
        val scanner = adapter.bluetoothLeScanner
            ?: return Attempt.Done(Prepared.Done(UnlockResult.Failed(Failure.BLUETOOTH_OFF)))
        val found = try {
            LockScanner(scanner).find(credential.localName, credential.address, SCAN_TIMEOUT_MS)
        } catch (e: ScanFailedException) {
            Log.w(TAG, "Scan failed")
            return Attempt.Retryable(Failure.LOCK_NOT_FOUND)
        }
        // Last resort on the second attempt: a direct connect to the stored
        // address, in case the advertisement was missed (e.g. the lock was
        // briefly connected to Home Assistant and not advertising).
        val device: BluetoothDevice = found?.device
            ?: credential.address?.takeIf { attempt == ATTEMPTS }?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
            ?: return Attempt.Retryable(Failure.LOCK_NOT_FOUND)

        onStage(Stage.CONNECTING)
        val transport = try {
            GattTransport.open(context, device, CONNECT_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Connect failed: ${e.javaClass.simpleName}")
            return Attempt.Retryable(Failure.CONNECTION_FAILED)
        }

        val key = credential.keyCopy()
        val session = YaleLockSession(
            transport = transport,
            offlineKey = key,
            keyIndex = credential.slot,
            randomBytes = { n -> ByteArray(n).also(random::nextBytes) },
            monotonicMillis = SystemClock::elapsedRealtime,
        )
        key.fill(0)
        val ready = Ready(transport, session)

        var handedOver = false
        try {
            onStage(Stage.AUTHENTICATING)
            session.handshake()
            bindIdentity(credential, found)

            onStage(Stage.CHECKING)
            if (session.lockStatus() == LockStatus.UNLOCKED) {
                return Attempt.Done(Prepared.Done(UnlockResult.AlreadyUnlocked))
            }
            handedOver = true
            return Attempt.Done(Prepared.Connected(ready))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Attempt $attempt failed at ${e.javaClass.simpleName}")
            return when (e) {
                is AuthException -> Attempt.Done(Prepared.Done(UnlockResult.Failed(Failure.AUTH_REJECTED)))
                is WriteNotSentException -> Attempt.Retryable(Failure.NOT_SENT)
                is ResponseException -> Attempt.Retryable(Failure.LOCK_ERROR)
                is ResponseTimeoutException, is DisconnectedException, is GattException ->
                    Attempt.Retryable(Failure.CONNECTION_FAILED)
                else -> Attempt.Retryable(Failure.LOCK_ERROR)
            }
        } finally {
            if (!handedOver) release(ready, onStage)
        }
    }

    /**
     * After a successful handshake the advertiser is proven to be our lock, so
     * remember its name/address if the credential did not carry them yet.
     */
    private fun bindIdentity(credential: LockCredential, found: LockScanner.Found?) {
        if (found == null || (credential.localName != null && credential.address != null)) return
        val name = found.localName?.takeIf { credential.localName == null }
        val address = found.device.address.takeIf { credential.address == null }
        if (name == null && address == null) return
        val updated = runCatching { credential.withIdentity(name, address) }.getOrNull() ?: return
        try {
            runCatching { store.save(updated) }.onFailure { Log.w(TAG, "Could not record lock identity") }
        } finally {
            updated.wipe()
        }
    }

    private fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "UnlockEngine"
        private const val ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 600L
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READBACK_TIMEOUT_MS = 4_000L
        private const val SHUTDOWN_TIMEOUT_MS = 3_000L

        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }
}
