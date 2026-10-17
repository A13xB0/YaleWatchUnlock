package uk.co.hectospark.yaleunlock.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.hectospark.yaleunlock.R
import uk.co.hectospark.yaleunlock.credential.CredentialStore
import uk.co.hectospark.yaleunlock.credential.LockCredential
import uk.co.hectospark.yaleunlock.credential.ProvisioningGate
import uk.co.hectospark.yaleunlock.protocol.LockStatus
import uk.co.hectospark.yaleunlock.tile.UnlockTileService
import uk.co.hectospark.yaleunlock.unlock.Failure
import uk.co.hectospark.yaleunlock.unlock.Stage
import uk.co.hectospark.yaleunlock.unlock.UnlockController
import uk.co.hectospark.yaleunlock.unlock.UnlockResult
import uk.co.hectospark.yaleunlock.unlock.UnlockService

private val Accent = Color(0xFFFFC94D)
private val OnAccent = Color(0xFF101820)
private val Ok = Color(0xFF32D583)
private val Warn = Color(0xFFFFB020)
private val Bad = Color(0xFFF97066)

private object Routes {
    const val HOME = "home"
    const val SETUP = "setup"
    const val ADB = "adb"
    const val MANUAL = "manual"
    const val SETTINGS = "settings"
}

@Composable
fun App(
    holdMode: Boolean,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onUnlock: () -> Unit,
    onExitHold: () -> Unit,
) {
    MaterialTheme {
        if (holdMode) {
            HoldToUnlockScreen(
                permissionsGranted = permissionsGranted,
                onNeedPermission = onRequestPermissions,
                onMore = onExitHold,
            )
            return@MaterialTheme
        }
        AppScaffold {
            val nav = rememberSwipeDismissableNavController()
            val home: () -> Unit = { nav.popBackStack(Routes.HOME, inclusive = false) }
            SwipeDismissableNavHost(navController = nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        permissionsGranted = permissionsGranted,
                        onRequestPermissions = onRequestPermissions,
                        onUnlock = onUnlock,
                        onSetup = { nav.navigate(Routes.SETUP) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.SETUP) {
                    SetupScreen(onAdb = { nav.navigate(Routes.ADB) }, onManual = { nav.navigate(Routes.MANUAL) })
                }
                composable(Routes.ADB) { AdbProvisionScreen(onDone = home) }
                composable(Routes.MANUAL) { ManualEntryScreen(onDone = home) }
                composable(Routes.SETTINGS) { SettingsScreen(onReplace = { nav.navigate(Routes.SETUP) }, onDeleted = home) }
            }
        }
    }
}

@Composable
private fun Screen(content: ScalingLazyListScope.() -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Top),
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
private fun Title(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.titleMedium,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
)

@Composable
private fun Body(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) = Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = color,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
)

@Composable
private fun HomeScreen(
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onUnlock: () -> Unit,
    onSetup: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val provisioned by CredentialStore.get(context).provisioned.collectAsStateWithLifecycle()
    val state by UnlockController.state.collectAsStateWithLifecycle()
    val working = UnlockController.busy
    val finished = (state as? UnlockController.State.Finished)?.result

    Screen {
        item { Title(stringResource(R.string.door_name)) }
        if (!provisioned) {
            item { Body(stringResource(R.string.not_set_up)) }
            item {
                Button(onClick = onSetup, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.set_up)) })
            }
            return@Screen
        }
        item {
            val label = stringResource(if (finished is UnlockResult.Ambiguous) R.string.try_again else R.string.unlock)
            Button(
                onClick = onUnlock,
                enabled = !working,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                icon = { Icon(painterResource(R.drawable.ic_unlock), contentDescription = null) },
                label = { Text(label, style = MaterialTheme.typography.titleMedium) },
            )
        }
        item {
            val (text, color) = statusLine(state)
            Body(text, color)
        }
        if (!permissionsGranted) {
            item { Body(stringResource(R.string.permission_needed), Warn) }
            item {
                OutlinedButton(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.grant_permission)) },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = onSettings,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings)) },
            )
        }
    }
}

@Composable
private fun statusLine(state: UnlockController.State): Pair<String, Color> = when (state) {
    UnlockController.State.Idle -> stringResource(R.string.tap_to_unlock) to MaterialTheme.colorScheme.onSurfaceVariant
    is UnlockController.State.Working -> stringResource(
        when (state.stage) {
            Stage.PREPARING -> R.string.stage_preparing
            Stage.SCANNING -> R.string.stage_scanning
            Stage.CONNECTING -> R.string.stage_connecting
            Stage.AUTHENTICATING -> R.string.stage_authenticating
            Stage.CHECKING -> R.string.stage_checking
            Stage.UNLOCKING -> R.string.stage_unlocking
            Stage.DISCONNECTING -> R.string.stage_disconnecting
        },
    ) to Accent
    is UnlockController.State.Holding -> stringResource(
        if (state.armed) R.string.widget_release_hint else R.string.widget_keep_holding,
    ) to Accent
    is UnlockController.State.Finished -> when (val r = state.result) {
        is UnlockResult.Unlocked -> stringResource(R.string.result_unlocked) to Ok
        UnlockResult.AlreadyUnlocked -> stringResource(R.string.result_already_unlocked) to Ok
        UnlockResult.Jammed -> stringResource(R.string.result_jammed) to Bad
        is UnlockResult.Ambiguous -> {
            val base = stringResource(R.string.result_ambiguous)
            val after = r.statusAfter?.let { " " + stringResource(R.string.result_ambiguous_status, statusName(it)) }.orEmpty()
            (base + after) to Warn
        }
        is UnlockResult.Failed -> stringResource(failureText(r.reason)) to Bad
    }
}

@Composable
private fun statusName(status: LockStatus): String = stringResource(
    when (status) {
        LockStatus.UNLOCKED -> R.string.status_unlocked
        LockStatus.UNLOCKING -> R.string.status_unlocking
        LockStatus.LOCKED -> R.string.status_locked
        LockStatus.LOCKING -> R.string.status_locking
        LockStatus.SECURE_MODE -> R.string.status_secure_mode
        LockStatus.JAMMED -> R.string.status_jammed
        else -> R.string.status_unknown
    },
)

private fun failureText(f: Failure): Int = when (f) {
    Failure.NOT_PROVISIONED -> R.string.failure_not_provisioned
    Failure.WATCH_LOCKED -> R.string.failure_watch_locked
    Failure.CREDENTIAL_UNREADABLE -> R.string.failure_credential_unreadable
    Failure.PERMISSION_MISSING -> R.string.failure_permission_missing
    Failure.BLUETOOTH_OFF -> R.string.failure_bluetooth_off
    Failure.LOCK_NOT_FOUND -> R.string.failure_lock_not_found
    Failure.CONNECTION_FAILED -> R.string.failure_connection_failed
    Failure.AUTH_REJECTED -> R.string.failure_auth_rejected
    Failure.LOCK_ERROR -> R.string.failure_lock_error
    Failure.NOT_SENT -> R.string.failure_not_sent
}

@Composable
private fun SetupScreen(onAdb: () -> Unit, onManual: () -> Unit) = Screen {
    item { Title(stringResource(R.string.setup_title)) }
    item { Button(onClick = onAdb, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.setup_adb)) }) }
    item {
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.setup_manual)) })
    }
    item { Body(stringResource(R.string.setup_help)) }
}

@Composable
private fun AdbProvisionScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val gate by ProvisioningGate.state.collectAsStateWithLifecycle()
    var remaining by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        ProvisioningGate.arm()
        onDispose { ProvisioningGate.reset() }
    }
    LaunchedEffect(gate) {
        val armed = gate as? ProvisioningGate.State.Armed
        if (gate is ProvisioningGate.State.Received) UnlockTileService.requestUpdate(context)
        while (armed != null) {
            remaining = ((armed.untilElapsed - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)
            if (remaining == 0) {
                ProvisioningGate.disarm()
                break
            }
            delay(1000)
        }
    }

    Screen {
        item { Title(stringResource(R.string.adb_title)) }
        when (val g = gate) {
            is ProvisioningGate.State.Armed -> {
                item { Body(stringResource(R.string.adb_instructions), MaterialTheme.colorScheme.onSurface) }
                item { Body(stringResource(R.string.adb_remaining, remaining), Accent) }
            }
            ProvisioningGate.State.Received -> {
                item { Body(stringResource(R.string.adb_received), Ok) }
                item { Button(onClick = onDone, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.done)) }) }
            }
            is ProvisioningGate.State.Rejected -> {
                item { Body(stringResource(R.string.adb_rejected, g.reason), Bad) }
                item { RearmButton() }
            }
            ProvisioningGate.State.Idle -> {
                item { Body(stringResource(R.string.adb_expired), Warn) }
                item { RearmButton() }
            }
        }
    }
}

@Composable
private fun RearmButton() = OutlinedButton(
    onClick = ProvisioningGate::arm,
    modifier = Modifier.fillMaxWidth(),
    label = { Text(stringResource(R.string.adb_rearm)) },
)

/**
 * Fallback setup: the 32-hex-digit key on a keypad, then the slot.
 * The key lives only in a CharArray that is wiped when the screen goes away.
 */
@Composable
private fun ManualEntryScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val buffer = remember { CharArray(32) }
    var length by remember { mutableIntStateOf(0) }
    var slot by remember { mutableIntStateOf(1) }
    var step by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<Pair<Int, Color>?>(null) }

    DisposableEffect(Unit) { onDispose { buffer.fill('\u0000') } }

    Screen {
        if (step == 0) {
            item { Title(stringResource(R.string.manual_key_title)) }
            item {
                // Only the most recent digit is ever shown.
                val masked = buildString {
                    for (i in 0 until length) {
                        if (i > 0 && i % 4 == 0) append(' ')
                        append(if (i == length - 1) buffer[i] else '•')
                    }
                }
                Body(masked.ifEmpty { "—" }, MaterialTheme.colorScheme.onSurface)
            }
            item { Body(stringResource(R.string.manual_key_progress, length)) }
            for (row in listOf("0123", "4567", "89AB", "CDEF")) {
                item {
                    KeyRow(row.map { c -> c.toString() to { if (length < 32) buffer[length++] = c } })
                }
            }
            item {
                KeyRow(listOf("⌫" to { if (length > 0) buffer[--length] = '\u0000' }))
            }
            item {
                Button(
                    onClick = { step = 1 },
                    enabled = length == 32,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.manual_next)) },
                )
            }
        } else {
            item { Title(stringResource(R.string.manual_slot_title)) }
            item { Text(slot.toString(), style = MaterialTheme.typography.displaySmall) }
            item {
                KeyRow(
                    listOf(
                        "−10" to { slot = (slot - 10).coerceAtLeast(0) },
                        "−" to { slot = (slot - 1).coerceAtLeast(0) },
                        "+" to { slot = (slot + 1).coerceAtMost(255) },
                        "+10" to { slot = (slot + 10).coerceAtMost(255) },
                    ),
                )
            }
            message?.let { (text, color) -> item { Body(stringResource(text), color) } }
            item {
                Button(
                    onClick = {
                        message = if (saveManual(context, buffer, slot)) R.string.manual_saved to Ok else R.string.manual_save_failed to Bad
                        if (message?.first == R.string.manual_saved) {
                            buffer.fill('\u0000')
                            length = 0
                            UnlockTileService.requestUpdate(context)
                        }
                    },
                    enabled = length == 32,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.save)) },
                )
            }
            if (message?.first == R.string.manual_saved) {
                item { OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.done)) }) }
            }
        }
    }
}

private fun saveManual(context: android.content.Context, hex: CharArray, slot: Int): Boolean {
    val key = ByteArray(LockCredential.KEY_LEN) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
    return try {
        val credential = LockCredential(key, slot, localName = null, address = null)
        try {
            CredentialStore.get(context).save(credential)
            true
        } finally {
            credential.wipe()
        }
    } catch (e: Exception) {
        false
    } finally {
        key.fill(0)
    }
}

@Composable
private fun KeyRow(keys: List<Pair<String, () -> Unit>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((label, action) in keys) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(if (label.length > 1) 46.dp else 40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(role = Role.Button, onClick = action)
                    .semantics { contentDescription = label },
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SettingsScreen(onReplace: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val store = CredentialStore.get(context)
    val provisioned by store.provisioned.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Screen {
        item { Title(stringResource(R.string.settings)) }
        item {
            Body(
                stringResource(R.string.settings_credential) + ": " +
                    stringResource(if (provisioned) R.string.settings_stored else R.string.settings_none),
                MaterialTheme.colorScheme.onSurface,
            )
        }
        item {
            Button(onClick = onReplace, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.settings_replace)) })
        }
        if (provisioned) {
            item {
                OutlinedButton(
                    onClick = {
                        if (!confirmDelete) {
                            confirmDelete = true
                        } else {
                            store.delete()
                            UnlockController.clear()
                            UnlockTileService.requestUpdate(context)
                            onDeleted()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            stringResource(if (confirmDelete) R.string.settings_delete_confirm else R.string.settings_delete),
                            color = Bad,
                        )
                    },
                )
            }
        }
    }
}

/** How long the button must be held before the unlock starts; shared with the widget. */
private const val HOLD_MS = UnlockService.HOLD_MS.toInt()

/**
 * Full-screen hold-to-unlock, opened from the tile.
 *
 * Finger down starts connecting straight away (the same service hold path
 * the widget uses), so the lock is usually ready by the time the ring fills.
 * At [HOLD_MS] the unlock is committed while still held; releasing or
 * swiping earlier cancels and drops the connection without sending anything.
 * Committing on time rather than on release is safe here because these touch
 * events are delivered in-process and cannot go missing.
 */
@Composable
private fun HoldToUnlockScreen(permissionsGranted: Boolean, onNeedPermission: () -> Unit, onMore: () -> Unit) {
    val context = LocalContext.current
    val state by UnlockController.state.collectAsStateWithLifecycle()
    val working = UnlockController.busy
    // Read through State so the gesture handler is never restarted mid-press.
    val busyNow by rememberUpdatedState(working)
    val canHold by rememberUpdatedState(permissionsGranted)
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Rewind the ring once a run has finished, leaving the result on screen.
    LaunchedEffect(state) {
        if (state is UnlockController.State.Finished && progress.value > 0f) {
            progress.animateTo(0f, tween(400))
        }
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        val track = MaterialTheme.colorScheme.surfaceContainer
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = stroke)
            drawArc(Accent, startAngle = -90f, sweepAngle = 360f * progress.value, useCenter = false, style = stroke)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val holdLabel = stringResource(R.string.hold_to_unlock)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(if (working) Accent.copy(alpha = 0.45f) else Accent)
                    .semantics { contentDescription = holdLabel }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (busyNow) return@detectTapGestures
                                if (!canHold) {
                                    onNeedPermission()
                                    return@detectTapGestures
                                }
                                UnlockController.clear()
                                // Start connecting now; the service ticks on start and buzzes at HOLD_MS.
                                UnlockService.sendHold(context, UnlockService.ACTION_HOLD_START)
                                var committed = false
                                val fill: Job = scope.launch {
                                    progress.snapTo(0f)
                                    progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                                    committed = true
                                    UnlockService.sendHold(context, UnlockService.ACTION_HOLD_END)
                                }
                                tryAwaitRelease()
                                if (!committed) {
                                    fill.cancel()
                                    UnlockService.sendHold(context, UnlockService.ACTION_HOLD_CANCEL)
                                    scope.launch { progress.animateTo(0f, tween(250)) }
                                }
                            },
                        )
                    },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painterResource(R.drawable.ic_unlock),
                        contentDescription = null,
                        tint = OnAccent,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = stringResource(if (working) R.string.unlocking_short else R.string.hold),
                        color = OnAccent,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            val (text, color) = holdStatusLine(state)
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 6.dp, start = 36.dp, end = 36.dp),
            )
            Text(
                text = stringResource(R.string.more),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !working, role = Role.Button, onClick = onMore)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun holdStatusLine(state: UnlockController.State): Pair<String, Color> =
    if (state is UnlockController.State.Idle) {
        stringResource(R.string.hold_hint) to MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        statusLine(state)
    }
