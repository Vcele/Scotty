package app.scotty

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.scotty.cast.CastController
import app.scotty.cast.CastService
import app.scotty.cast.CastTarget
import app.scotty.system.Beam
import app.scotty.system.Diagnostics
import app.scotty.ui.HomeScreen
import app.scotty.ui.MicRationaleDialog
import app.scotty.ui.ScottyTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) CastService.start(this, res.resultCode, data)
        else CastController.pendingTarget = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.startDiscovery()
    }

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val t = pendingAfterPermission
        pendingAfterPermission = null
        if (granted && t != null) requestProjection(t)
    }
    private var pendingAfterPermission: CastTarget? = null
    private var micRationaleFor by mutableStateOf<CastTarget?>(null)

    /** Discovery needs these up front; the microphone permission is asked for when casting. */
    private val permissions = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScottyTheme {
                HomeScreen(
                    vm = vm,
                    onCast = ::cast,
                    onStop = { CastService.stop(this) },
                    onDiagnostics = { Diagnostics.share(this) },
                )
                micRationaleFor?.let { target ->
                    MicRationaleDialog(
                        onContinue = {
                            micRationaleFor = null
                            pendingAfterPermission = target
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onCancel = { micRationaleFor = null },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) vm.startDiscovery() else permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onStop() {
        super.onStop()
        vm.stopDiscovery()
    }

    private fun cast(target: CastTarget) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micRationaleFor = target
            return
        }
        requestProjection(target)
    }

    private fun requestProjection(target: CastTarget) {
        CastController.pendingTarget = target
        projectionLauncher.launch(Beam.projectionIntent(this))
    }
}
