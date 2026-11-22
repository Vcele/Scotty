package app.scotty.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scotty.cast.Prefs
import app.scotty.shell.PowerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The name TVs show for this phone (its Wi-Fi Direct device name). */
@Composable
fun MiracastNameRow() {
    val ctx = LocalContext.current
    val phoneName = remember { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) ?: "this phone" }
    var name by remember { mutableStateOf(Prefs.miracastName(ctx)) }
    var editing by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable { editing = true },
        leadingContent = { Icon(Icons.Rounded.Tv, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text("Name shown on the TV") },
        supportingContent = { Text(name ?: "$phoneName (phone name)") },
        trailingContent = { Icon(Icons.Rounded.Edit, "Change") },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    if (editing) {
        MiracastNameDialog(
            current = name ?: phoneName,
            onSave = { new ->
                name = new
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun MiracastNameDialog(current: String, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val status by PowerMode.status.collectAsStateWithLifecycle()
    val power = status == PowerMode.Status.Ready
    var text by remember { mutableStateOf(current) }
    val scope = rememberCoroutineScope()

    fun apply(value: String?) {
        Prefs.setMiracastName(ctx, value)
        onSave(value)
        if (value != null && power) {
            scope.launch {
                val r = withContext(Dispatchers.IO) { PowerMode.setP2pDeviceName(value) }
                Toast.makeText(ctx, if (r == "ok") "TVs will now see “$value”" else "Couldn't rename: $r", Toast.LENGTH_LONG).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Tv, null) },
        title = { Text("Name shown on the TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(32) }, // Wi-Fi Direct device names are limited to 32 bytes
                    singleLine = true,
                    label = { Text("Device name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (power) "Applied right away with Power mode. It's the phone's Wi-Fi Direct name, so other devices see it too."
                    else "Android only lets privileged apps rename Wi-Fi Direct. Start Shizuku (Power mode) and Scotty applies it " +
                        "automatically – or change the phone's name, which Wi-Fi Direct uses on Pixels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!power) {
                    TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                    }) { Text("Open phone name settings") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { apply(text.trim().ifEmpty { null }) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { apply(null) }) { Text("Use phone name") }
        },
    )
}
