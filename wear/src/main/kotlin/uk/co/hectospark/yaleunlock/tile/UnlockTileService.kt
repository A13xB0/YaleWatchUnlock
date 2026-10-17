package uk.co.hectospark.yaleunlock.tile

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.content.edit
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.security.MessageDigest
import java.security.SecureRandom
import uk.co.hectospark.yaleunlock.R
import uk.co.hectospark.yaleunlock.credential.CredentialStore
import uk.co.hectospark.yaleunlock.ui.MainActivity

/**
 * A per-install secret carried by the tile's launch action.
 *
 * MainActivity must be exported for the tile renderer to start it, which
 * means any app on the watch could start it too. Only a launch carrying this
 * token opens straight into the hold-to-unlock screen; even then nothing
 * happens without a deliberate 3-second hold.
 */
object LaunchToken {
    private const val PREFS = "launch_token"
    private const val KEY = "token"

    @Synchronized
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit { putString(KEY, token) }
        return token
    }

    fun matches(context: Context, candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), get(context).toByteArray())
}

/** One big Unlock button; a tap opens the full-screen hold-to-unlock button. */
class UnlockTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(tile(CredentialStore.get(this).provisioned.value))
            "UnlockTileService#onTileRequest"
        }

    @Suppress("DEPRECATION") // The resource callback works on every renderer; see the layout note below.
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = CallbackToFutureAdapter.getFuture { completer ->
        completer.set(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(
                    ID_ICON,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_unlock).build(),
                        )
                        .build(),
                )
                .build(),
        )
        "UnlockTileService#onTileResourcesRequest"
    }

    private fun tile(provisioned: Boolean): TileBuilders.Tile =
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(layout(provisioned)).build())
                            .build(),
                    )
                    .build(),
            )
            .build()

    // Image.setResourceId is deprecated in favour of a scope API that needs a newer renderer.
    @Suppress("DEPRECATION")
    private fun layout(provisioned: Boolean): LayoutElementBuilders.LayoutElement {
        val label = getString(if (provisioned) R.string.unlock else R.string.tile_set_up)
        val button = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(BUTTON_DP))
            .setHeight(dp(BUTTON_DP))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(if (provisioned) COLOR_ACCENT else COLOR_SURFACE))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(BUTTON_DP / 2)).build())
                            .build(),
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(ID_UNLOCK)
                            .setOnClick(launch(autoUnlock = provisioned))
                            .build(),
                    )
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(label).build())
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        LayoutElementBuilders.Image.Builder()
                            .setResourceId(ID_ICON)
                            .setWidth(dp(34f))
                            .setHeight(dp(34f))
                            .setColorFilter(
                                LayoutElementBuilders.ColorFilter.Builder()
                                    .setTint(argb(if (provisioned) COLOR_ON_ACCENT else COLOR_ON_SURFACE))
                                    .build(),
                            )
                            .build(),
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(label)
                            .setMaxLines(1)
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(15f))
                                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                    .setColor(argb(if (provisioned) COLOR_ON_ACCENT else COLOR_ON_SURFACE))
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(getString(R.string.door_name))
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder().setSize(sp(13f)).setColor(argb(COLOR_MUTED)).build(),
                            )
                            .build(),
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
                    .addContent(button)
                    .build(),
            )
            .build()
    }

    private fun launch(autoUnlock: Boolean): ActionBuilders.Action {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(MainActivity::class.java.name)
        if (autoUnlock) {
            activity.addKeyToExtraMapping(
                MainActivity.EXTRA_LAUNCH_TOKEN,
                ActionBuilders.AndroidStringExtra.Builder().setValue(LaunchToken.get(this)).build(),
            )
        }
        return ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val ID_ICON = "unlock_icon"
        private const val ID_UNLOCK = "unlock"
        private const val BUTTON_DP = 104f
        private const val COLOR_ACCENT = 0xFFFFC94D.toInt()
        private const val COLOR_ON_ACCENT = 0xFF101820.toInt()
        private const val COLOR_SURFACE = 0xFF1E252E.toInt()
        private const val COLOR_ON_SURFACE = 0xFFE6E8EC.toInt()
        private const val COLOR_MUTED = 0xFF8D95A3.toInt()

        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(UnlockTileService::class.java)
        }
    }
}
