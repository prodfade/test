package com.example.lumenplayer

import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class Track(val uri: Uri, val title: String, val artist: String, val album: String, val art: android.graphics.Bitmap?, val color: Color, val duration: Long)
private enum class Page { Home, Library, Equalizer, Settings }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        setContent { LumenApp(sharedUris = sharedAudioUris(intent)) }
    }
}

@Suppress("DEPRECATION")
private fun sharedAudioUris(intent: android.content.Intent?): List<Uri> {
    if (intent == null) return emptyList()
    val uris = when (intent.action) {
        android.content.Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM), intent.data)
        android.content.Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(android.content.Intent.EXTRA_STREAM).orEmpty()
        android.content.Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        else -> emptyList()
    }
    return uris.distinct()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LumenApp(sharedUris: List<Uri> = emptyList()) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lumen", 0) }
    val player = remember { ExoPlayer.Builder(context).build() }
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var current by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(Page.Library) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
    var accentMode by remember { mutableIntStateOf(prefs.getInt("accent", 0)) }
    var favorite by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val palettes = listOf(Color(0xFFD9A7FF), Color(0xFFFF7D8D), Color(0xFF77D8C3), Color(0xFFFFC66D), Color(0xFF8FAEFF))
    val accent = tracks.getOrNull(current)?.color ?: palettes[accentMode % palettes.size]
    val scheme = if (dark) darkColorScheme(primary = accent, onPrimary = Color(0xFF21152D), secondary = Color(0xFFFFB2C0), background = Color(0xFF101014), surface = Color(0xFF19191F), surfaceVariant = Color(0xFF25252C), onSurface = Color(0xFFF3EFF7), onSurfaceVariant = Color(0xFFBDB8C4)) else lightColorScheme(primary = accent, onPrimary = Color(0xFF24152E), secondary = Color(0xFF8B3852), background = Color(0xFFF8F3FA), surface = Color(0xFFFFF8FF), surfaceVariant = Color(0xFFEDE5F1), onSurface = Color(0xFF211D25), onSurfaceVariant = Color(0xFF625B67))
    SideEffect {
        val baseFlags = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        context.windowOrNull()?.decorView?.systemUiVisibility = baseFlags or (if (dark) 0 else (android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR))
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(playing) {
        while (playing) { if (player.duration > 0) progress = player.currentPosition.toFloat() / player.duration; kotlinx.coroutines.delay(400) }
    }
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            sharedUris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            message = "Adding shared audio…"
            val imported = withContext(Dispatchers.IO) { scanUris(context, sharedUris) }
            tracks = (tracks + imported).distinctBy { it.uri }.sortedBy { it.title.lowercase(Locale.ROOT) }
            message = if (imported.isEmpty()) "Couldn't read the shared audio file" else "Added ${imported.size} shared songs"
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            message = "Scanning folder…"
            scope.launch {
                val found = withContext(Dispatchers.IO) { scanFolder(context, uri) }
                tracks = found
                message = if (found.isEmpty()) "No supported audio found in that folder" else "Added ${found.size} songs"
            }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            message = "Importing audio…"
            scope.launch {
                val imported = withContext(Dispatchers.IO) { scanUris(context, uris) }
                tracks = (tracks + imported).distinctBy { it.uri }.sortedBy { it.title.lowercase(Locale.ROOT) }
                message = if (imported.isEmpty()) "No supported audio found in those files" else "Added ${imported.size} songs"
            }
        }
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(selected = page == Page.Home, onClick = { page = Page.Home }, icon = { Icon(Icons.Rounded.GraphicEq, null) }, label = { Text("Listen") })
                NavigationBarItem(selected = page == Page.Library, onClick = { page = Page.Library }, icon = { Icon(Icons.Rounded.QueueMusic, null) }, label = { Text("Library") })
                NavigationBarItem(selected = page == Page.Equalizer, onClick = { page = Page.Equalizer }, icon = { Icon(Icons.Rounded.Equalizer, null) }, label = { Text("Sound") })
                NavigationBarItem(selected = page == Page.Settings, onClick = { page = Page.Settings }, icon = { Icon(Icons.Rounded.Tune, null) }, label = { Text("Style") })
            }
        }) { padding ->
            AnimatedContent(page, modifier = Modifier.fillMaxSize().padding(padding), label = "page") { active ->
                when (active) {
                    Page.Home -> PlayerScreen(tracks.getOrNull(current), playing, progress, favorite, tracks.size, accent, onPlay = { togglePlayback(player, tracks, current) }, onPrev = { if (tracks.isNotEmpty()) { current = (current - 1 + tracks.size) % tracks.size; playIndex(player, tracks, current) } }, onNext = { if (tracks.isNotEmpty()) { current = (current + 1) % tracks.size; playIndex(player, tracks, current) } }, onFavorite = { favorite = !favorite }, onSeek = { progress = it; if (player.duration > 0) player.seekTo((player.duration * it).toLong()) }, onLibrary = { page = Page.Library }, onEqualizer = { page = Page.Equalizer }, onFolder = { folderPicker.launch(null) }, onQueue = { expanded = true })
                    Page.Library -> LibraryScreen(tracks, current, message, accent, onChooseFolder = { folderPicker.launch(null) }, onImportFiles = { audioPicker.launch(arrayOf("audio/*")) }, onSelect = { current = it; playIndex(player, tracks, it); page = Page.Home }, onBack = { page = Page.Home })
                    Page.Equalizer -> EqualizerScreen(player.audioSessionId, accent, onBack = { page = Page.Home })
                    Page.Settings -> SettingsScreen(dark, accentMode, accent, onDark = { dark = it; prefs.edit().putBoolean("dark", it).apply() }, onAccent = { accentMode = it; prefs.edit().putInt("accent", it).apply() }, onFolder = { folderPicker.launch(null) })
                }
            }
        }
        if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Text("Up next", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            if (tracks.isEmpty()) Text("Choose a music folder to build your queue.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            tracks.forEachIndexed { index, track -> TrackRow(track, index == current, accent) { current = index; playIndex(player, tracks, index); expanded = false } }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PlayerScreen(track: Track?, playing: Boolean, progress: Float, favorite: Boolean, count: Int, accent: Color, onPlay: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onFavorite: () -> Unit, onSeek: (Float) -> Unit, onLibrary: () -> Unit, onEqualizer: () -> Unit, onFolder: () -> Unit, onQueue: () -> Unit) {
    val c by animateColorAsState(accent, label = "art accent")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.copy(alpha = .38f), MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onLibrary) { Icon(Icons.Rounded.MusicNote, "Library") }
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp); Text("Lumen", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
                IconButton(onClick = onQueue) { Icon(Icons.Rounded.MoreVert, "Queue") }
            }
            Spacer(Modifier.height(16.dp))
            ArtCard(track, Modifier.fillMaxWidth().aspectRatio(1f), c)
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track?.title ?: "Your next favorite", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track?.let { "${it.artist}  ·  ${it.album}" } ?: "Choose a folder to start listening", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite", tint = if (favorite) Color(0xFFFF718B) else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(18.dp))
            Slider(value = progress.coerceIn(0f, 1f), onValueChange = onSeek, colors = SliderDefaults.colors(thumbColor = c, activeTrackColor = c))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(track?.let { fmt((it.duration * progress).toLong()) } ?: "0:00", style = MaterialTheme.typography.labelSmall); Text(track?.let { fmt(it.duration) } ?: "0:00", style = MaterialTheme.typography.labelSmall) }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onQueue) { Icon(Icons.Rounded.QueueMusic, "Queue", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = onPrev, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", modifier = Modifier.size(34.dp)) }
                FilledIconButton(onClick = onPlay, modifier = Modifier.size(72.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = c, contentColor = Color(0xFF21152D))) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", modifier = Modifier.size(38.dp)) }
                IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(34.dp)) }
                IconButton(onClick = onEqualizer) { Icon(Icons.Rounded.Equalizer, "Equalizer", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onFolder, modifier = Modifier.weight(1f), shape = RoundedCornerShape(22.dp)) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(if (count == 0) "Choose music folder" else "$count songs") }
                OutlinedButton(onClick = onLibrary, shape = RoundedCornerShape(22.dp)) { Icon(Icons.Rounded.QueueMusic, null); Spacer(Modifier.width(6.dp)); Text("Library") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ArtCard(track: Track?, modifier: Modifier = Modifier, accent: Color) {
    Box(modifier.clip(RoundedCornerShape(34.dp)).background(Brush.linearGradient(listOf(accent, accent.copy(alpha = .45f), MaterialTheme.colorScheme.surfaceVariant))), contentAlignment = Alignment.Center) {
        if (track?.art != null) Image(track.art.asImageBitmap(), null, Modifier.fillMaxSize()) else {
            Canvas(Modifier.fillMaxSize()) { drawCircle(Color.White.copy(alpha = .06f), size.minDimension * .42f); drawCircle(Color.White.copy(alpha = .12f), size.minDimension * .30f); drawCircle(accent.copy(alpha = .44f), size.minDimension * .17f) }
            Icon(Icons.Rounded.Album, null, Modifier.size(80.dp), tint = Color.White.copy(alpha = .92f))
        }
    }
}

@Composable
private fun LibraryScreen(tracks: List<Track>, current: Int, message: String, accent: Color, onChooseFolder: () -> Unit, onImportFiles: () -> Unit, onSelect: (Int) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }; Column(Modifier.weight(1f)) { Text("Your library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${tracks.size} songs", color = MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(onClick = onImportFiles) { Icon(Icons.Rounded.LibraryAdd, "Import audio files") }; IconButton(onClick = onChooseFolder) { Icon(Icons.Rounded.CreateNewFolder, "Scan folder") } }
        if (tracks.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(54.dp), tint = accent); Spacer(Modifier.height(14.dp)); Text(if (message.isNotBlank()) message else "Your music, beautifully together", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(14.dp)); Text("Scan a music folder or import audio files. Android restricts access to Android/data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(14.dp)); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = onChooseFolder) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose folder") }; OutlinedButton(onClick = onImportFiles) { Icon(Icons.Rounded.LibraryAdd, null); Spacer(Modifier.width(8.dp)); Text("Import files") } } } }
        else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) { itemsIndexed(tracks) { index, track -> TrackRow(track, index == current, accent) { onSelect(index) } } }
    }
}

@Composable
private fun TrackRow(track: Track, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = .24f)), contentAlignment = Alignment.Center) { if (track.art != null) Image(track.art.asImageBitmap(), null, Modifier.fillMaxSize()) else Icon(Icons.Rounded.Album, null, tint = accent) }
        Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) accent else MaterialTheme.colorScheme.onSurface); Text("${track.artist} · ${track.album}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }; Text(fmt(track.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqualizerScreen(sessionId: Int, accent: Color, onBack: () -> Unit) {
    val eq = remember(sessionId) { runCatching { Equalizer(0, sessionId).apply { enabled = true } }.getOrNull() }
    DisposableEffect(eq) { onDispose { runCatching { eq?.release() } } }
    val count = eq?.numberOfBands?.toInt() ?: 5
    val range = eq?.bandLevelRange?.let { it[0].toFloat()..it[1].toFloat() } ?: (-1200f..1200f)
    var values by remember(eq) { mutableStateOf(List(count) { i -> runCatching { eq?.getBandLevel(i.toShort())?.toFloat() ?: 0f }.getOrDefault(0f) }) }
    val names = remember(eq, count) { List(count) { i -> runCatching { "${eq?.getCenterFreq(i.toShort())?.div(1000)} Hz" }.getOrDefault(listOf("60 Hz", "230 Hz", "910 Hz", "4 kHz", "14 kHz").getOrElse(i) { "${i + 1}" }) } }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }; Text("Sound studio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Switch(checked = eq?.enabled ?: false, onCheckedChange = { runCatching { eq?.enabled = it } }) }
        Text("Make every detail yours.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        var preset by remember { mutableStateOf("Custom") }
        var presetOpen by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = presetOpen, onExpandedChange = { presetOpen = it }) {
            OutlinedTextField(value = preset, onValueChange = {}, readOnly = true, label = { Text("Preset") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetOpen) }, modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(20.dp))
            ExposedDropdownMenu(expanded = presetOpen, onDismissRequest = { presetOpen = false }) {
                listOf("Custom", "Bass boost", "Vocal", "Treble", "Flat").forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        preset = name; presetOpen = false
                        val gains = when (name) { "Bass boost" -> listOf(.75f, .5f, .1f, 0f, 0f); "Vocal" -> listOf(-.2f, .1f, .45f, .35f, -.1f); "Treble" -> listOf(-.25f, -.1f, 0f, .35f, .7f); else -> List(count) { 0f } }
                        values = values.indices.map { i -> (gains.getOrElse(i) { 0f } * range.endInclusive).coerceIn(range.start, range.endInclusive) }
                        values.forEachIndexed { i, v -> runCatching { eq?.setBandLevel(i.toShort(), v.toInt().toShort()) } }
                    })
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            values.indices.forEach { index -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(values[index] / 100).toInt()} dB", style = MaterialTheme.typography.labelMedium)
                Box(Modifier.width(54.dp).height(280.dp), contentAlignment = Alignment.Center) {
                    Slider(value = values[index], onValueChange = { v -> values = values.toMutableList().also { it[index] = v }; preset = "Custom"; runCatching { eq?.setBandLevel(index.toShort(), v.toInt().toShort()) } }, valueRange = range, modifier = Modifier.width(230.dp).height(48.dp).graphicsLayer(rotationZ = -90f), colors = SliderDefaults.colors(activeTrackColor = accent, thumbColor = accent, inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant))
                }
                Text(names[index], style = MaterialTheme.typography.labelSmall, maxLines = 1, fontSize = 9.sp)
            } }
        }
        val bassBoost = remember(sessionId) { runCatching { android.media.audiofx.BassBoost(0, sessionId).apply { enabled = true } }.getOrNull() }
        DisposableEffect(bassBoost) { onDispose { runCatching { bassBoost?.release() } } }
        val virtualizer = remember(sessionId) { runCatching { android.media.audiofx.Virtualizer(0, sessionId).apply { enabled = true } }.getOrNull() }
        DisposableEffect(virtualizer) { onDispose { runCatching { virtualizer?.release() } } }
        Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(18.dp)) {
                Text("Bass & space", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Tune the low end and widen the stage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                var bass by remember(bassBoost) { mutableFloatStateOf(runCatching { (bassBoost?.roundedStrength ?: 0).toFloat() / 1000f }.getOrDefault(0f)) }
                Text("Bass boost", style = MaterialTheme.typography.labelMedium)
                Slider(value = bass, onValueChange = { bass = it; runCatching { bassBoost?.setStrength((it * 1000).toInt().toShort()) } })
                var space by remember(virtualizer) { mutableFloatStateOf(runCatching { (virtualizer?.roundedStrength ?: 0).toFloat() / 1000f }.getOrDefault(0f)) }
                Text("Surround width", style = MaterialTheme.typography.labelMedium)
                Slider(value = space, onValueChange = { space = it; runCatching { virtualizer?.setStrength((it * 1000).toInt().toShort()) } })
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsScreen(dark: Boolean, accentMode: Int, accent: Color, onDark: (Boolean) -> Unit, onAccent: (Int) -> Unit, onFolder: () -> Unit) {
    val colors = listOf(Color(0xFFD9A7FF), Color(0xFFFF7D8D), Color(0xFF77D8C3), Color(0xFFFFC66D), Color(0xFF8FAEFF))
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)) {
        Text("Make it yours", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
        Text("Little details, tuned to you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Card(shape = RoundedCornerShape(28.dp)) { Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.DarkMode, null, tint = accent); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text("Dark appearance", fontWeight = FontWeight.SemiBold); Text("A softer listening room", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = dark, onCheckedChange = onDark) } }
        Spacer(Modifier.height(22.dp)); Text("Accent palette", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("Or let album art set the mood.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { colors.forEachIndexed { index, color -> Box(Modifier.size(48.dp).clip(CircleShape).background(color).clickable { onAccent(index) }, contentAlignment = Alignment.Center) { if (accentMode == index) Icon(Icons.Rounded.Check, null, tint = Color(0xFF24152E)) } } }
        Spacer(Modifier.height(24.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
        ListItem(headlineContent = { Text("Music folders") }, supportingContent = { Text("Select internal storage or an SD card") }, leadingContent = { Icon(Icons.Rounded.FolderOpen, null, tint = accent) }, trailingContent = { Icon(Icons.Rounded.ChevronRight, null) }, modifier = Modifier.clickable(onClick = onFolder), colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        ListItem(headlineContent = { Text("Album art colors") }, supportingContent = { Text("Accent follows the current artwork") }, leadingContent = { Icon(Icons.Rounded.Palette, null, tint = accent) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        ListItem(headlineContent = { Text("Playback") }, supportingContent = { Text("Gapless local playback powered by Media3") }, leadingContent = { Icon(Icons.Rounded.GraphicEq, null, tint = accent) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

private suspend fun scanFolder(context: android.content.Context, root: Uri): List<Track> = withContext(Dispatchers.IO) {
    val files = mutableListOf<DocumentFile>()
    fun walk(folder: DocumentFile, depth: Int) { if (depth > 12) return; folder.listFiles().forEach { file -> if (file.isDirectory) walk(file, depth + 1) else if (file.type?.startsWith("audio/") == true || file.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma")) files.add(file) } }
    runCatching { walk(DocumentFile.fromTreeUri(context, root) ?: return@withContext emptyList(), 0) }
    scanUris(context, files.map { it.uri })
}

private suspend fun scanUris(context: android.content.Context, uris: List<Uri>): List<Track> = withContext(Dispatchers.IO) {
    uris.mapNotNull { uri ->
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val name = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
        if (mime?.startsWith("audio/") != true && name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) !in setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma")) return@mapNotNull null
        val retriever = MediaMetadataRetriever()
        var art: android.graphics.Bitmap? = null
        var title = name?.substringBeforeLast('.') ?: "Unknown track"; var artist = "Unknown artist"; var album = "Local music"; var duration = 0L
        runCatching {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: title
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: artist
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: album
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            art = retriever.embeddedPicture?.let { bytes -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { android.graphics.Bitmap.createScaledBitmap(it, 500, 500, true) } }
        }
        runCatching { retriever.release() }
        val color = art?.let { averageColor(it) } ?: Color(0xFFD9A7FF)
        Track(uri, title, artist, album, art, color, duration)
    }.sortedBy { it.title.lowercase(Locale.ROOT) }
}

private fun averageColor(bitmap: android.graphics.Bitmap): Color {
    var red = 0f; var green = 0f; var blue = 0f; var total = 0f
    val hsv = FloatArray(3)
    val stepX = (bitmap.width / 18).coerceAtLeast(1)
    val stepY = (bitmap.height / 18).coerceAtLeast(1)
    for (y in 0 until bitmap.height step stepY) for (x in 0 until bitmap.width step stepX) {
        val pixel = bitmap.getPixel(x, y)
        android.graphics.Color.colorToHSV(pixel, hsv)
        val weight = .25f + hsv[1]
        red += android.graphics.Color.red(pixel) / 255f * weight
        green += android.graphics.Color.green(pixel) / 255f * weight
        blue += android.graphics.Color.blue(pixel) / 255f * weight
        total += weight
    }
    var sampled = Color(red / total, green / total, blue / total)
    if (sampled.red + sampled.green + sampled.blue < .42f) sampled = Color(0xFFB89AE8)
    if (sampled.red + sampled.green + sampled.blue > 2.5f) sampled = Color(0xFFE0A0BF)
    return sampled
}

private fun playIndex(player: ExoPlayer, tracks: List<Track>, index: Int) { if (tracks.isEmpty()) return; player.setMediaItems(tracks.map { MediaItem.fromUri(it.uri) }, index, 0L); player.prepare(); player.play() }
private fun togglePlayback(player: ExoPlayer, tracks: List<Track>, current: Int) { if (player.mediaItemCount == 0 && tracks.isNotEmpty()) playIndex(player, tracks, current) else if (player.isPlaying) player.pause() else player.play() }
private fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

private fun android.content.Context.windowOrNull(): android.view.Window? {
    var current: android.content.Context = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current.window
        current = current.baseContext
    }
    return (current as? android.app.Activity)?.window
}
