package uk.co.hectospark.yaleunlock.unlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.hectospark.yaleunlock.R
import uk.co.hectospark.yaleunlock.ui.MainActivity
import uk.co.hectospark.yaleunlock.widget.UnlockWidget

/** Process-wide unlock state; the single source the UI and widget observe. */
object UnlockController {

    sealed interface State {
        data object Idle : State
        data class Working(val stage: Stage) : State

        /** Finger down on the widget. [armed] once held long enough to unlock on release. */
        data class Holding(val stage: Stage, val armed: Boolean) : State
        data class Finished(val result: UnlockResult, val atElapsed: Long) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val busy: Boolean get() = _state.value.let { it is State.Working || it is State.Holding }

    /**
     * Starts a tap-style unlock unless one is running or one just finished (a
     * double tap must never become two operations). Returns whether it started.
     */
    @Synchronized
    fun start(context: Context): Boolean {
        if (!canStart()) return false
        _state.value = State.Working(Stage.PREPARING)
        try {
            context.startForegroundService(Intent(context, UnlockService::class.java))
        } catch (e: IllegalStateException) {
            // Background start refused; nothing was sent to the lock.
            finish(UnlockResult.Failed(Failure.PERMISSION_MISSING))
            return false
        }
        return true
    }

    @Synchronized
    internal fun canStart(): Boolean = when (val s = _state.value) {
        is State.Working, is State.Holding -> false
        is State.Finished -> SystemClock.elapsedRealtime() - s.atElapsed >= DEBOUNCE_MS
        State.Idle -> true
    }

    fun clear() {
        if (_state.value is State.Finished) _state.value = State.Idle
    }

    internal fun set(state: State) {
        _state.value = state
    }

    internal fun stage(stage: Stage) {
        when (val s = _state.value) {
            is State.Working -> _state.value = State.Working(stage)
            is State.Holding -> _state.value = s.copy(stage = stage)
            else -> Unit
        }
    }

    internal fun finish(result: UnlockResult) {
        _state.value = State.Finished(result, SystemClock.elapsedRealtime())
    }

    private const val DEBOUNCE_MS = 2_000L
}

/**
 * Foreground service (type connectedDevice) that owns unlock runs, so they
 * complete and disconnect cleanly when the screen turns off or the app
 * leaves the foreground.
 *
 * Two entry points:
 * - plain start (app button): prepare, unlock, release.
 * - the widget's hold gesture: [ACTION_HOLD_START] prepares the link while
 *   the finger is down; [ACTION_HOLD_END] unlocks only if the hold lasted at
 *   least [HOLD_MS]. A missing or cancelled release, or a hold past
 *   [MAX_HOLD_MS], disconnects without unlocking. Every lost event therefore
 *   fails safe.
 *
 * START_NOT_STICKY on purpose: if the process dies mid-run, the system must
 * not restart the service and replay an unlock nobody asked for.
 */
class UnlockService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine by lazy { UnlockEngine(applicationContext) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var runJob: Job? = null

    // Hold gesture state, confined to the main thread.
    private var holdStartedAt = 0L
    private var holdPrepare: Deferred<UnlockEngine.Prepared>? = null
    private var holdTimers: Job? = null
    private var widgetUpdates: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Keep the widget in step with whatever the lock is doing.
        widgetUpdates = scope.launch {
            UnlockController.state.collect { runCatching { UnlockWidget.refresh(applicationContext) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_HOLD_START -> beginHold()
            ACTION_HOLD_END -> endHold(released = true)
            ACTION_HOLD_CANCEL -> endHold(released = false)
            else -> runTap()
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean = try {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
    } catch (e: Exception) {
        // Missing Bluetooth permission makes the connectedDevice type unavailable.
        Log.w(TAG, "Could not enter foreground: ${e.javaClass.simpleName}")
        if (!UnlockController.busy || UnlockController.state.value is UnlockController.State.Working) {
            UnlockController.finish(UnlockResult.Failed(Failure.PERMISSION_MISSING))
        }
        stopIfIdle()
        false
    }

    private fun runTap() {
        if (runJob?.isActive == true || holdPrepare != null) return
        acquireWakeLock()
        runJob = scope.launch {
            val result = try {
                withContext(Dispatchers.Default) { engine.run(::stageOnMain) }
            } catch (e: Exception) {
                Log.e(TAG, "Unlock crashed: ${e.javaClass.simpleName}")
                UnlockResult.Failed(Failure.LOCK_ERROR)
            }
            UnlockController.finish(result)
            haptic(result)
            stopIfIdle()
        }
    }

    private fun beginHold() {
        if (holdPrepare != null || runJob?.isActive == true) return
        if (!UnlockController.canStart()) return
        acquireWakeLock()
        holdStartedAt = SystemClock.elapsedRealtime()
        UnlockController.set(UnlockController.State.Holding(Stage.PREPARING, armed = false))
        tick()
        // Pre-unlock work only: scan, connect, authenticate, read status.
        holdPrepare = scope.async(Dispatchers.Default) { engine.prepare(::stageOnMain) }
        holdTimers = scope.launch {
            delay(HOLD_MS)
            (UnlockController.state.value as? UnlockController.State.Holding)?.let {
                UnlockController.set(it.copy(armed = true))
                tick(strong = true)
            }
            delay(MAX_HOLD_MS - HOLD_MS)
            Log.i(TAG, "Hold timed out without release")
            endHold(released = false)
        }
    }

    private fun endHold(released: Boolean) {
        val prepare = holdPrepare ?: return stopIfIdle()
        holdPrepare = null
        holdTimers?.cancel()
        val held = SystemClock.elapsedRealtime() - holdStartedAt
        val commit = released && held in HOLD_MS..MAX_HOLD_MS
        Log.i(TAG, "Hold ended after ${held}ms, commit=$commit")

        if (!commit) prepare.cancel()
        runJob = scope.launch {
            val prepared = runCatching { prepare.await() }.getOrNull()
            if (!commit) {
                (prepared as? UnlockEngine.Prepared.Connected)?.let {
                    withContext(Dispatchers.Default) { engine.release(it.ready, ::stageOnMain) }
                }
                UnlockController.set(UnlockController.State.Idle)
                stopIfIdle()
                return@launch
            }
            UnlockController.set(UnlockController.State.Working(Stage.UNLOCKING))
            val result = when (prepared) {
                is UnlockEngine.Prepared.Done -> prepared.result
                is UnlockEngine.Prepared.Connected -> withContext(Dispatchers.Default) {
                    try {
                        engine.commit(prepared.ready, ::stageOnMain)
                    } finally {
                        engine.release(prepared.ready, ::stageOnMain)
                    }
                }
                null -> UnlockResult.Failed(Failure.LOCK_ERROR)
            }
            UnlockController.finish(result)
            haptic(result)
            stopIfIdle()
        }
    }

    private fun stageOnMain(stage: Stage) {
        scope.launch { UnlockController.stage(stage) }
    }

    private fun stopIfIdle() {
        if (holdPrepare != null || runJob?.isActive == true) return
        wakeLock?.takeIf { it.isHeld }?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YaleUnlock:unlock")
            .apply { acquire(WAKE_LOCK_MS) }
    }

    override fun onDestroy() {
        widgetUpdates?.cancel()
        holdPrepare?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        when (UnlockController.state.value) {
            is UnlockController.State.Working -> UnlockController.finish(UnlockResult.Ambiguous(null))
            is UnlockController.State.Holding -> UnlockController.set(UnlockController.State.Idle)
            else -> Unit
        }
        super.onDestroy()
    }

    private fun vibrate(effect: Int) {
        runCatching {
            getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(VibrationEffect.createPredefined(effect))
        }
    }

    private fun tick(strong: Boolean = false) =
        vibrate(if (strong) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK)

    private fun haptic(result: UnlockResult) = vibrate(
        when (result) {
            is UnlockResult.Unlocked, UnlockResult.AlreadyUnlocked -> VibrationEffect.EFFECT_DOUBLE_CLICK
            else -> VibrationEffect.EFFECT_HEAVY_CLICK
        },
    )

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_unlock)
            .setContentTitle(getString(R.string.notification_unlocking))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "UnlockService"
        private const val CHANNEL_ID = "unlock"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_MS = 90_000L

        const val ACTION_HOLD_START = "uk.co.hectospark.yaleunlock.HOLD_START"
        const val ACTION_HOLD_END = "uk.co.hectospark.yaleunlock.HOLD_END"
        const val ACTION_HOLD_CANCEL = "uk.co.hectospark.yaleunlock.HOLD_CANCEL"

        /** How long the widget must be held before release unlocks. */
        const val HOLD_MS = 3_000L

        /** A hold this long without a release is treated as lost and abandoned. */
        const val MAX_HOLD_MS = 15_000L

        /** PendingIntent the widget fires for a hold event; the service itself is not exported. */
        fun holdIntent(context: Context, action: String): PendingIntent = PendingIntent.getForegroundService(
            context,
            action.hashCode(),
            Intent(context, UnlockService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
