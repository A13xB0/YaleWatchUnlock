package uk.co.hectospark.yaleunlock.widget

import android.content.Context
import androidx.compose.remote.creation.compose.action.pendingIntentAction
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.onTouchCancel
import androidx.compose.remote.creation.compose.modifier.onTouchDown
import androidx.compose.remote.creation.compose.modifier.onTouchUp
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.glance.wear.AssociateWithGlanceWearWidget
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.GlanceWearWidgetService
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.color
import androidx.glance.wear.core.WearWidgetParams
import uk.co.hectospark.yaleunlock.R
import uk.co.hectospark.yaleunlock.credential.CredentialStore
import uk.co.hectospark.yaleunlock.unlock.Stage
import uk.co.hectospark.yaleunlock.unlock.UnlockController
import uk.co.hectospark.yaleunlock.unlock.UnlockResult
import uk.co.hectospark.yaleunlock.unlock.UnlockService

/**
 * Wear OS widget: one big button you hold to unlock.
 *
 * Touch down, up and cancel are forwarded to [UnlockService] as PendingIntents.
 * The service connects while the finger is down, buzzes at 3 s, and unlocks on
 * release only if the hold lasted at least that long. The widget itself holds
 * no logic, so a missed event can only ever mean "nothing happens".
 */
class UnlockWidget : GlanceWearWidget() {

    override suspend fun provideWidgetData(context: Context, params: WearWidgetParams): WearWidgetData {
        val provisioned = CredentialStore.get(context).provisioned.value
        val face = face(context, provisioned, UnlockController.state.value)
        return WearWidgetDocument(background = WearWidgetBrush.color(Color.Black.rc)) {
            HoldButton(face, interactive = provisioned)
        }
    }

    /** What the button shows; computed in the app, rendered remotely. */
    data class Face(val title: String, val subtitle: String, val fill: Long, val ink: Long)

    companion object {
        private const val ACCENT = 0xFFFFC94D
        private const val ARMED = 0xFF32D583
        private const val BUSY = 0xFF8A7230
        private const val SURFACE = 0xFF1E252E
        private const val INK_DARK = 0xFF101820
        private const val INK_LIGHT = 0xFFE6E8EC
        private const val BAD = 0xFFF97066

        suspend fun refresh(context: Context) {
            UnlockWidget().triggerUpdateAll(context)
        }

        internal fun face(context: Context, provisioned: Boolean, state: UnlockController.State): Face {
            fun s(id: Int) = context.getString(id)
            if (!provisioned) return Face(s(R.string.door_name), s(R.string.widget_set_up), SURFACE, INK_LIGHT)
            return when (state) {
                UnlockController.State.Idle ->
                    Face(s(R.string.door_name), s(R.string.hold_hint), ACCENT, INK_DARK)
                is UnlockController.State.Holding ->
                    if (state.armed) {
                        Face(s(R.string.widget_release), s(R.string.widget_release_hint), ARMED, INK_DARK)
                    } else {
                        Face(s(R.string.widget_keep_holding), stageText(context, state.stage), BUSY, INK_LIGHT)
                    }
                is UnlockController.State.Working ->
                    Face(s(R.string.unlocking_short), stageText(context, state.stage), BUSY, INK_LIGHT)
                is UnlockController.State.Finished -> when (state.result) {
                    is UnlockResult.Unlocked, UnlockResult.AlreadyUnlocked ->
                        Face(s(R.string.result_unlocked), s(R.string.hold_hint), ARMED, INK_DARK)
                    is UnlockResult.Ambiguous ->
                        Face(s(R.string.widget_unclear), s(R.string.widget_check_door), ACCENT, INK_DARK)
                    else -> Face(s(R.string.widget_failed), s(R.string.widget_open_app), BAD, INK_DARK)
                }
            }
        }

        private fun stageText(context: Context, stage: Stage): String = context.getString(
            when (stage) {
                Stage.PREPARING -> R.string.stage_preparing
                Stage.SCANNING -> R.string.stage_scanning
                Stage.CONNECTING -> R.string.stage_connecting
                Stage.AUTHENTICATING -> R.string.stage_authenticating
                Stage.CHECKING -> R.string.stage_checking
                Stage.UNLOCKING -> R.string.stage_unlocking
                Stage.DISCONNECTING -> R.string.stage_disconnecting
            },
        )
    }
}

@RemoteComposable
@Composable
private fun HoldButton(face: UnlockWidget.Face, interactive: Boolean) {
    var modifier = RemoteModifier
        .fillMaxSize()
        .clip(RemoteRoundedCornerShape(28.rdp))
        .background(Color(face.fill).rc)
    if (interactive) {
        modifier = modifier
            .onTouchDown(pendingIntentAction { UnlockService.holdIntent(it, UnlockService.ACTION_HOLD_START) })
            .onTouchUp(pendingIntentAction { UnlockService.holdIntent(it, UnlockService.ACTION_HOLD_END) })
            .onTouchCancel(pendingIntentAction { UnlockService.holdIntent(it, UnlockService.ACTION_HOLD_CANCEL) })
    }
    RemoteBox(modifier = modifier, contentAlignment = RemoteAlignment.Center) {
        RemoteColumn(
            verticalArrangement = RemoteArrangement.Center,
            horizontalAlignment = RemoteAlignment.CenterHorizontally,
        ) {
            RemoteText(
                text = face.title.rs,
                color = Color(face.ink).rc,
                fontSize = 20.rsp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            RemoteText(
                text = face.subtitle.rs,
                color = Color(face.ink).rc,
                fontSize = 13.rsp,
                // Passing a FontWeight keeps this on the public RemoteText overload.
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** Binds the widget into the system's widget carousel. */
@AssociateWithGlanceWearWidget(UnlockWidget::class)
class UnlockWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = UnlockWidget()
}
