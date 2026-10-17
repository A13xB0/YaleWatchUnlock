package uk.co.hectospark.yaleunlock.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.util.size
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.hectospark.yaleunlock.credential.LockCredential
import uk.co.hectospark.yaleunlock.protocol.YaleGatt
import uk.co.hectospark.yaleunlock.protocol.YaleProtocolException

class ScanFailedException(code: Int) : YaleProtocolException("BLE scan failed ($code)")

/**
 * Pure identification rules, mirroring yalexs-ble `PushLock.update_advertisement`:
 * accept an advertisement when its local name matches the stored per-device
 * name, otherwise when the address matches. The name is preferred because it
 * survives address changes.
 *
 * yalexs-ble calls a name unique at 7 characters; that count includes the
 * NUL terminator the module advertises, so after normalisation it is 6.
 */
object LockMatcher {
    const val UNIQUE_LOCAL_NAME_MIN = 6

    fun isYale(manufacturerIds: Set<Int>, serviceUuids: Set<UUID>): Boolean =
        YaleGatt.YALE_MANUFACTURER_ID in manufacturerIds || YaleGatt.SERVICE in serviceUuids

    fun matches(wantName: String?, wantAddress: String?, advName: String?, advAddress: String): Boolean {
        val want = LockCredential.normaliseLocalName(wantName)
        if (want != null && want.length >= UNIQUE_LOCAL_NAME_MIN && LockCredential.normaliseLocalName(advName) == want) {
            return true
        }
        return wantAddress != null && advAddress.equals(wantAddress, ignoreCase = true)
    }
}

/** Finds the configured lock by its advertisements. */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_SCAN first.
class LockScanner(private val scanner: BluetoothLeScanner) {

    data class Found(val device: BluetoothDevice, val localName: String?, val rssi: Int)

    /**
     * Returns the matching lock, or null after [timeoutMs].
     *
     * Without any stored identity (manual setup), returns the strongest Yale
     * advertiser seen within [NEAREST_WINDOW_MS] of the first one; the
     * handshake then proves whether the key belongs to it.
     */
    suspend fun find(localName: String?, address: String?, timeoutMs: Long): Found? {
        val unbound = localName == null && address == null
        val results = Channel<ScanResult>(Channel.UNLIMITED)
        val failed = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                results.trySend(result)
            }

            override fun onBatchScanResults(batch: MutableList<ScanResult>) {
                batch.forEach { results.trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                failed.complete(errorCode)
            }
        }

        scanner.startScan(FILTERS, SETTINGS, callback)
        try {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var best: Found? = null
            var windowEnd = Long.MAX_VALUE
            while (true) {
                val remaining = minOf(deadline, windowEnd) - SystemClock.elapsedRealtime()
                if (remaining <= 0) return best
                val r = withTimeoutOrNull(remaining) {
                    select<ScanResult> {
                        results.onReceive { it }
                        failed.onAwait { throw ScanFailedException(it) }
                    }
                } ?: continue
                val record = r.scanRecord ?: continue
                val name = LockCredential.normaliseLocalName(record.deviceName)
                if (!unbound) {
                    if (LockMatcher.matches(localName, address, name, r.device.address)) return Found(r.device, name, r.rssi)
                    continue
                }
                val mfr = buildSet {
                    record.manufacturerSpecificData?.let { for (i in 0 until it.size) add(it.keyAt(i)) }
                }
                val uuids = record.serviceUuids.orEmpty().map { it.uuid }.toSet()
                if (!LockMatcher.isYale(mfr, uuids)) continue
                if (best == null) windowEnd = SystemClock.elapsedRealtime() + NEAREST_WINDOW_MS
                if (best == null || r.rssi > best.rssi) best = Found(r.device, name, r.rssi)
            }
        } finally {
            runCatching { scanner.stopScan(callback) }
            results.close()
        }
    }

    private companion object {
        const val NEAREST_WINDOW_MS = 1_500L

        // OR-ed: the Yale company ID or the Yale service (Home Assistant's
        // matcher uses both). A filter is also what keeps a scan running with
        // the screen off. Identity is checked in code, so neighbouring Yale
        // locks are ignored.
        val FILTERS: List<ScanFilter> = listOf(
            ScanFilter.Builder().setManufacturerData(YaleGatt.YALE_MANUFACTURER_ID, ByteArray(0)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(YaleGatt.SERVICE)).build(),
        )

        val SETTINGS: ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
    }
}
