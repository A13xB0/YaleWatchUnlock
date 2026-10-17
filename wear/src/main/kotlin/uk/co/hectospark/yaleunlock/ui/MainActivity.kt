package uk.co.hectospark.yaleunlock.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import uk.co.hectospark.yaleunlock.credential.CredentialStore
import uk.co.hectospark.yaleunlock.tile.LaunchToken
import uk.co.hectospark.yaleunlock.unlock.UnlockController
import uk.co.hectospark.yaleunlock.unlock.UnlockEngine

class MainActivity : ComponentActivity() {

    private val permissionsGranted = mutableStateOf(false)

    /** True when opened from the tile: show only the hold-to-unlock button. */
    private val holdMode = mutableStateOf(false)
    private var unlockAfterPermission = false

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
        if (unlockAfterPermission && permissionsGranted.value) UnlockController.start(this)
        unlockAfterPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        setContent {
            App(
                holdMode = holdMode.value,
                permissionsGranted = permissionsGranted.value,
                onRequestPermissions = { requestPermissions(unlockAfter = false) },
                onUnlock = ::unlock,
                onExitHold = { holdMode.value = false },
            )
        }
        handleLaunch(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UnlockController.state.collect { state ->
                    // Keep the screen (and the result) visible while the lock is being worked.
                    if (state is UnlockController.State.Working || state is UnlockController.State.Holding) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    /**
     * A launch carrying the tile's per-install token opens the hold-to-unlock
     * screen; any other launch opens the normal app. Neither unlocks by itself:
     * the hold screen still needs a deliberate 3-second press.
     */
    private fun handleLaunch(intent: Intent?) {
        val token = intent?.getStringExtra(EXTRA_LAUNCH_TOKEN)
        val fromTile = LaunchToken.matches(this, token) && CredentialStore.get(this).provisioned.value
        if (fromTile && UnlockController.state.value !is UnlockController.State.Working) UnlockController.clear()
        holdMode.value = fromTile
    }

    private fun unlock() {
        if (!permissionsGranted.value) {
            requestPermissions(unlockAfter = true)
            return
        }
        UnlockController.start(this)
    }

    private fun requestPermissions(unlockAfter: Boolean) {
        unlockAfterPermission = unlockAfter
        permissionRequest.launch(UnlockEngine.REQUIRED_PERMISSIONS)
    }

    private fun refreshPermissions() {
        permissionsGranted.value = UnlockEngine.REQUIRED_PERMISSIONS.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        const val EXTRA_LAUNCH_TOKEN = "uk.co.hectospark.yaleunlock.LAUNCH_TOKEN"
    }
}
