package app.beamory.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.beamory.MainViewModel
import app.beamory.airplay.AirPlayDevice
import app.beamory.cast.CastController
import app.beamory.cast.CastState
import app.beamory.cast.CastTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onCast: (CastTarget) -> Unit, onStop: () -> Unit) {
    val state by CastController.state.collectAsStateWithLifecycle()
    val wfd by vm.miracast.devices.collectAsStateWithLifecycle()
    val wfdScanning by vm.miracast.scanning.collectAsStateWithLifecycle()
    val wfdError by vm.miracast.error.collectAsStateWithLifecycle()
    val airplay by vm.airplay.devices.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var airplayMode by rememberSaveable { mutableIntStateOf(0) } // 0 = audio, 1 = screen
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Beamory") },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Rounded.Refresh, "Search again") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AnimatedVisibility(
                    visible = state is CastState.Active,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    (state as? CastState.Active)?.let { ActiveCard(it, onStop) }
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = tab == 0, onClick = { tab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = { Icon(Icons.Rounded.Tv, null, Modifier.size(18.dp)) },
                    ) { Text("Miracast") }
                    SegmentedButton(
                        selected = tab == 1, onClick = { tab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = { Icon(Icons.Rounded.Airplay, null, Modifier.size(18.dp)) },
                    ) { Text("AirPlay") }
                }
            }
            if (tab == 0) {
                item { HintCard(Icons.AutoMirrored.Rounded.ScreenShare, "Mirror your whole screen with sound to any Miracast TV, projector or stick – no Wi-Fi network needed. Make sure screen mirroring / “Screen Share” is open on the TV.") }
                item { SectionHeader("Nearby displays", wfdScanning) }
                wfdError?.let { err -> item { ErrorLine(err) } }
                if (wfd.isEmpty()) item { EmptyState("Looking for Miracast displays…") }
                items(wfd, key = { it.address }) { d ->
                    DeviceRow(
                        icon = if (d.isMiracastSink || d.isDisplay) Icons.Rounded.Tv else Icons.Rounded.Devices,
                        title = d.name,
                        subtitle = listOfNotNull(
                            if (d.isMiracastSink) "Miracast display" else "Wi-Fi Direct device",
                            d.statusText,
                        ).joinToString(" · "),
                        enabled = true,
                        highlighted = d.isMiracastSink,
                        onClick = { onCast(CastTarget.Miracast(d.name, d.address)) },
                    )
                }
            } else {
                item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = airplayMode == 0, onClick = { airplayMode = 0 },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            icon = { Icon(Icons.Rounded.MusicNote, null, Modifier.size(18.dp)) },
                        ) { Text("Audio only") }
                        SegmentedButton(
                            selected = airplayMode == 1, onClick = { airplayMode = 1 },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            icon = { Icon(Icons.AutoMirrored.Rounded.ScreenShare, null, Modifier.size(18.dp)) },
                        ) { Text("Screen + audio") }
                    }
                }
                item {
                    AnimatedContent(airplayMode, label = "hint") { m ->
                        if (m == 0) HintCard(Icons.Rounded.MusicNote, "Streams everything your phone plays – Spotify, YouTube Music, podcasts – lossless to AirPlay speakers and TVs. Control playback in the app as usual.")
                        else HintCard(Icons.Rounded.Info, "Sends your screen and sound as a live video stream to Apple TV and AirPlay TVs. Runs a few seconds behind, so it suits videos and slides more than games.")
                    }
                }
                item { SectionHeader("AirPlay receivers on this network", airplay.isEmpty()) }
                if (airplay.isEmpty()) item { EmptyState("Looking for AirPlay receivers on your Wi-Fi…") }
                items(airplay, key = { it.name }) { d ->
                    val usable = if (airplayMode == 0) d.supportsAudio else d.supportsVideo
                    DeviceRow(
                        icon = if (d.isSpeaker) Icons.Rounded.Speaker else Icons.Rounded.Tv,
                        title = d.name,
                        subtitle = airplaySubtitle(d, airplayMode, usable),
                        enabled = usable,
                        highlighted = false,
                        trailingLock = d.needsPassword,
                        onClick = {
                            onCast(
                                if (airplayMode == 0) CastTarget.AirPlayAudio(d.name, d.host, d.raopPort!!)
                                else CastTarget.AirPlayScreen(d.name, d.host, d.airplayPort!!),
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun airplaySubtitle(d: AirPlayDevice, mode: Int, usable: Boolean): String {
    if (!usable) return if (mode == 0) "No audio receiver" else "Audio only – no video support"
    val kind = when {
        d.isAppleTv -> "Apple TV"
        d.model?.startsWith("AudioAccessory") == true -> "HomePod"
        d.supportsVideo -> "AirPlay TV / video"
        else -> "AirPlay speaker"
    }
    return listOfNotNull(kind, d.host).joinToString(" · ")
}

@Composable
private fun ActiveCard(s: CastState.Active, onStop: () -> Unit) {
    val container by animateColorAsState(
        if (s.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, label = "c",
    )
    val onContainer = if (s.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingIcon(if (s.error) Icons.Rounded.ErrorOutline else Icons.Rounded.CastConnected, pulsing = s.live)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (s.error) "Couldn't beam to ${s.target.name}" else s.target.name,
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (s.target) {
                            is CastTarget.Miracast -> "Miracast screen mirroring"
                            is CastTarget.AirPlayAudio -> "AirPlay audio"
                            is CastTarget.AirPlayScreen -> "AirPlay screen + audio"
                        },
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.alpha(0.8f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(s.status, style = MaterialTheme.typography.bodyMedium)
            if (!s.live && !s.error) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (s.target is CastTarget.AirPlayAudio && !s.error) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeDown, null)
                    Slider(
                        value = s.volume, onValueChange = { CastController.setVolume(it) },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, null)
                }
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onStop, modifier = Modifier.align(Alignment.End)) {
                Text(if (s.error) "Dismiss" else "Stop beaming")
            }
        }
    }
}

@Composable
private fun PulsingIcon(icon: ImageVector, pulsing: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val scale by t.animateFloat(1f, 1.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    Box(
        Modifier.size(52.dp).scale(if (pulsing) scale else 1f).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(28.dp)) }
}

@Composable
private fun HintCard(icon: ImageVector, text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(text: String, busy: Boolean) {
    Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun EmptyState(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
    )
}

@Composable
private fun ErrorLine(text: String) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DeviceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    highlighted: Boolean,
    trailingLock: Boolean = false,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            leadingContent = {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(
                        if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon, null,
                        tint = if (highlighted) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            },
            headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = if (trailingLock) {
                { Icon(Icons.Rounded.Lock, "Password protected") }
            } else null,
        )
    }
}

