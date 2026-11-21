package app.scotty.system

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scotty.airplay.AirPlayDiscovery
import app.scotty.cast.CastController
import app.scotty.cast.CastService
import app.scotty.cast.CastState
import app.scotty.cast.CastTarget
import app.scotty.cast.Prefs
import app.scotty.ui.PinDialog
import app.scotty.ui.ScottyTheme

/**
 * Compact "Play audio on…" sheet over whatever the user is doing – the system-level entry point
 * used by the Quick Settings tile and the launcher shortcut. Mirrors the iOS AirPlay picker.
 */
class BeamActivity : ComponentActivity() {
    private lateinit var discovery: AirPlayDiscovery
    private var pending: CastTarget? = null
    private var awaitingStart by androidx.compose.runtime.mutableStateOf(false)

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            CastService.start(this, res.resultCode, data)
            awaitingStart = true // sheet closes once live; stays for a PIN prompt
        } else {
            CastController.pendingTarget = null
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let { beam(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discovery = AirPlayDiscovery(this)
        val direct = intent.getBooleanExtra(EXTRA_DIRECT, false)
        val last = Prefs.lastTarget(this)
        if (direct && last != null && savedInstanceState == null && !CastController.isActive) beam(last)
        setContent { ScottyTheme { Sheet() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val last = Prefs.lastTarget(this)
        if (intent.getBooleanExtra(EXTRA_DIRECT, false) && last != null && !CastController.isActive) beam(last)
    }

    override fun onStart() {
        super.onStart()
        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < 33
        ) discovery.start()
    }

    override fun onStop() {
        super.onStop()
        discovery.stop()
    }

    private fun beam(t: CastTarget) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pending = t
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        pending = null
        CastController.pendingTarget = t
        projection.launch(Beam.projectionIntent(this))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Sheet() {
        val state by CastController.state.collectAsStateWithLifecycle()
        val devices by discovery.devices.collectAsStateWithLifecycle()
        val active = state as? CastState.Active
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        androidx.compose.runtime.LaunchedEffect(awaitingStart, active?.live) {
            if (awaitingStart && active?.live == true) {
                kotlinx.coroutines.delay(700)
                awaitingStart = false
                finish()
            }
        }

        active?.takeIf { it.pinRequested }?.let { s ->
            PinDialog(s.target.name, onSubmit = CastController::submitPin, onCancel = { CastService.stop(this) })
        }

        ModalBottomSheet(onDismissRequest = { finish() }, sheetState = sheet) {
            Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
                Text("Play audio on", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(if (active == null) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                ) {
                    Entry(Icons.Rounded.PhoneAndroid, "This phone", null, selected = active == null) {
                        CastService.stop(this@BeamActivity)
                    }
                }
                Spacer(Modifier.height(4.dp))
                val speakers = devices.filter { it.supportsAudio }
                speakers.forEach { d ->
                    val selected = active?.target?.name == d.name
                    val sub = when {
                        selected && active != null && !active.live && !active.error -> active.status
                        selected && active?.error == true -> active.status
                        else -> null
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    ) {
                        Entry(if (d.isSpeaker) Icons.Rounded.Speaker else Icons.Rounded.Tv, d.name, sub, selected) {
                            if (!selected) beam(CastTarget.AirPlayAudio(d.name, d.host, d.raopPort!!, if (d.prefersAirPlay2) 2 else 0))
                        }
                    }
                }
                if (speakers.isEmpty()) {
                    Text(
                        "Looking for AirPlay speakers…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
                if (active != null && active.target is CastTarget.AirPlayAudio && active.live) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null)
                        Slider(active.volume, CastController::setVolume, Modifier.weight(1f).padding(horizontal = 8.dp))
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null)
                    }
                }
            }
        }
    }

    @Composable
    private fun Entry(icon: ImageVector, title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
        ListItem(
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null) }
            },
            headlineContent = { Text(title) },
            supportingContent = subtitle?.let { { Text(it) } },
            trailingContent = if (selected) ({ Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary) }) else null,
        )
    }

    companion object {
        const val EXTRA_DIRECT = "direct"
    }
}
