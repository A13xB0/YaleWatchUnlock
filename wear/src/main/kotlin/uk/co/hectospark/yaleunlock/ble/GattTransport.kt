package uk.co.hectospark.yaleunlock.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.hectospark.yaleunlock.protocol.DisconnectedException
import uk.co.hectospark.yaleunlock.protocol.FrameTransport
import uk.co.hectospark.yaleunlock.protocol.WriteNotSentException
import uk.co.hectospark.yaleunlock.protocol.YaleChannel
import uk.co.hectospark.yaleunlock.protocol.YaleGatt
import uk.co.hectospark.yaleunlock.protocol.YaleProtocolException

/** A GATT step failed after it was queued, or the lock lacks the Yale service. */
class GattException(message: String) : YaleProtocolException(message)

/**
 * [FrameTransport] over one BluetoothGatt connection.
 *
 * Android allows a single outstanding GATT operation, so every operation is
 * serialised through [opMutex] and completed from the callback. Every wait
 * also races the disconnect signal, so a dropped link fails fast instead of
 * waiting out a timeout. Notifications are queued per channel in arrival
 * order, which the session's CBC chain depends on.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT before creating a transport.
class GattTransport private constructor(private val device: BluetoothDevice) : FrameTransport {

    private val opMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<Int>? = null
    private val connected = CompletableDeferred<Unit>()
    private val servicesDiscovered = CompletableDeferred<Int>()
    private val disconnected = CompletableDeferred<Int>()
    private val queues = YaleChannel.entries.associateWith { Channel<ByteArray>(Channel.UNLIMITED) }
    private val characteristics = mutableMapOf<UUID, BluetoothGattCharacteristic>()
    private var gatt: BluetoothGatt? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Link down (status $status)")
                val error = DisconnectedException("Link lost (status $status)")
                disconnected.complete(status)
                connected.completeExceptionally(error)
                servicesDiscovered.completeExceptionally(error)
                pending?.completeExceptionally(error)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDiscovered.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val channel = when (c.uuid) {
                YaleGatt.SECURE_READ -> YaleChannel.SECURE
                YaleGatt.READ -> YaleChannel.SESSION
                else -> return
            }
            queues.getValue(channel).trySend(value.copyOf())
        }
    }

    private suspend fun connect(context: Context, timeoutMs: Long) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw GattException("connectGatt returned null")
        withTimeoutOrNull(timeoutMs) { connected.await() } ?: throw DisconnectedException("Connect timed out")

        if (gatt?.discoverServices() != true) throw GattException("Service discovery not started")
        val status = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { servicesDiscovered.await() }
            ?: throw GattException("Service discovery timed out")
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("Service discovery failed ($status)")

        val service = gatt?.getService(YaleGatt.SERVICE) ?: throw GattException("Yale service not found")
        for (uuid in listOf(YaleGatt.WRITE, YaleGatt.READ, YaleGatt.SECURE_WRITE, YaleGatt.SECURE_READ)) {
            characteristics[uuid] = service.getCharacteristic(uuid) ?: throw GattException("Yale characteristic missing")
        }
        // Only after discovery: some stacks refuse discoverServices() while a parameter update is pending.
        // A short exchange benefits from the fastest interval; the lock drops idle links after ~30 s anyway.
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    override suspend fun enableNotifications(channel: YaleChannel) = setNotifications(channel, enable = true)

    override suspend fun disableNotifications(channel: YaleChannel) = setNotifications(channel, enable = false)

    private suspend fun setNotifications(channel: YaleChannel, enable: Boolean) {
        val g = requireGatt()
        val c = characteristics.getValue(readUuid(channel))
        if (!g.setCharacteristicNotification(c, enable)) throw GattException("setCharacteristicNotification failed")
        val cccd = c.getDescriptor(YaleGatt.CCCD) ?: throw GattException("CCCD missing")
        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val status = operation { g.writeDescriptor(cccd, value) }
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("CCCD write failed ($status)")
    }

    override suspend fun write(channel: YaleChannel, frame: ByteArray) {
        val g = requireGatt()
        val c = characteristics.getValue(writeUuid(channel))
        val status = operation {
            g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        // Queued but rejected: the lock may still have seen it, so this is not "not sent".
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("Write failed ($status)")
    }

    override suspend fun receive(channel: YaleChannel, timeoutMs: Long): ByteArray? {
        val queue = queues.getValue(channel)
        queue.tryReceive().getOrNull()?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            select {
                queue.onReceive { it }
                disconnected.onAwait { throw DisconnectedException("Link lost while waiting for reply") }
            }
        }
    }

    override fun poll(channel: YaleChannel): ByteArray? = queues.getValue(channel).tryReceive().getOrNull()

    /**
     * Runs one GATT operation to completion. [start] returns a
     * BluetoothStatusCodes value; anything but SUCCESS means nothing was queued.
     */
    private suspend fun operation(start: () -> Int): Int = opMutex.withLock { runOperation(start) }

    private suspend fun runOperation(start: () -> Int): Int {
        if (disconnected.isCompleted) throw DisconnectedException("Not connected")
        var attempt = 0
        while (true) {
            val done = CompletableDeferred<Int>()
            pending = done
            val rc = start()
            if (rc == BluetoothStatusCodes.SUCCESS) {
                try {
                    return withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
                        select {
                            done.onAwait { it }
                            disconnected.onAwait { throw DisconnectedException("Link lost during GATT operation") }
                        }
                    } ?: throw GattException("GATT operation timed out")
                } finally {
                    pending = null
                }
            }
            pending = null
            // The stack is still busy with the previous request: safe to retry, nothing was queued.
            if (rc == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && attempt++ < BUSY_RETRIES) {
                delay(BUSY_BACKOFF_MS)
                continue
            }
            throw WriteNotSentException("GATT operation not queued ($rc)")
        }
    }

    /** Disconnects and releases the GATT client. Safe to call more than once. */
    suspend fun close() {
        val g = gatt ?: return
        gatt = null
        if (!disconnected.isCompleted) {
            runCatching { g.disconnect() }
            withTimeoutOrNull(DISCONNECT_WAIT_MS) { disconnected.await() }
        }
        runCatching { g.close() }
        queues.values.forEach { q -> generateSequence { q.tryReceive().getOrNull() }.forEach { it.fill(0) } }
    }

    private fun requireGatt(): BluetoothGatt = gatt ?: throw DisconnectedException("Not connected")

    private fun readUuid(channel: YaleChannel) = if (channel == YaleChannel.SECURE) YaleGatt.SECURE_READ else YaleGatt.READ
    private fun writeUuid(channel: YaleChannel) = if (channel == YaleChannel.SECURE) YaleGatt.SECURE_WRITE else YaleGatt.WRITE

    companion object {
        private const val TAG = "GattTransport"
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
        private const val OPERATION_TIMEOUT_MS = 5_000L
        private const val DISCONNECT_WAIT_MS = 1_500L
        private const val BUSY_RETRIES = 5
        private const val BUSY_BACKOFF_MS = 40L

        /** Connects, discovers and verifies the Yale service, or throws and cleans up. */
        suspend fun open(context: Context, device: BluetoothDevice, timeoutMs: Long): GattTransport {
            val transport = GattTransport(device)
            try {
                transport.connect(context.applicationContext, timeoutMs)
            } catch (e: Throwable) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { transport.close() }
                throw e
            }
            return transport
        }
    }
}
