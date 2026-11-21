package app.scotty

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.scotty.cast.CastController
import app.scotty.cast.CastService
import app.scotty.cast.CastTarget
import app.scotty.ui.ScottyTheme
import app.scotty.ui.HomeScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) CastService.start(this, res.resultCode, data)
        else CastController.pendingTarget = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.startDiscovery()
        pendingAfterPermission?.let { pendingAfterPermission = null; requestProjection(it) }
    }
    private var pendingAfterPermission: CastTarget? = null

    private val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
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
                )
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
            pendingAfterPermission = target
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        requestProjection(target)
    }

    private fun requestProjection(target: CastTarget) {
        CastController.pendingTarget = target
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }
}
