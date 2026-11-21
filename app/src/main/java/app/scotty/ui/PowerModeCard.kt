package app.scotty.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scotty.cast.Prefs
import app.scotty.shell.PowerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional Shizuku-based "power mode" with a one-time permanent setup. */
@Composable
fun PowerModeCard() {
    val ctx = LocalContext.current
    val status by PowerMode.status.collectAsStateWithLifecycle()
    var results by remember { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    var applying by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(Prefs.grantsDone(ctx)) }
    val scope = rememberCoroutineScope()

    fun apply() {
        applying = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { PowerMode.applyPermanentGrants() }
            results = r
            applying = false
            if (r.count { it.second } >= 3) {
                done = true
                Prefs.setGrantsDone(ctx, true)
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            PowerMode.refresh()
            delay(2000)
        }
    }
    // The whole point: once Shizuku is connected the first time, make everything permanent.
    LaunchedEffect(status) {
        if (status == PowerMode.Status.Ready && !done && !applying) apply()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Power mode (Shizuku)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                when (status) {
                    PowerMode.Status.Ready -> Text("Active", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    PowerMode.Status.Connecting -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else -> {}
                }
            }
            Text(
                when (status) {
                    PowerMode.Status.NoShizuku ->
                        if (done) "Permanent setup is done. Start Shizuku after a reboot to also get true rerouting and audio from apps that block capture."
                        else "With Shizuku, Scotty starts without prompts, moves sound off the phone instead of muting it, and also captures apps that block audio capture. One-time setup – the permissions stay after reboots."
                    PowerMode.Status.NeedsPermission -> "Shizuku is running. Allow Scotty to use it to finish the one-time setup."
                    PowerMode.Status.Connecting -> "Connecting to Shizuku…"
                    PowerMode.Status.Ready ->
                        if (done) "No prompts, sound moves to the speaker, and apps that block capture are included."
                        else "Applying the one-time setup…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            results?.let { list ->
                list.forEach { (label, ok) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ok) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(16.dp),
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    PowerMode.Status.NoShizuku -> FilledTonalButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
                    }) { Text("Get Shizuku") }
                    PowerMode.Status.NeedsPermission -> FilledTonalButton(onClick = PowerMode::requestPermission) { Text("Allow Scotty") }
                    PowerMode.Status.Ready -> if (done) TextButton(onClick = ::apply, enabled = !applying) { Text("Re-apply setup") }
                    else -> {}
                }
            }
        }
    }
}
